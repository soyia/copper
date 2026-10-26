package com.soyia.copperv2.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ElectronicCredentialParser {
    private static final Pattern VERSION = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");

    public ParseResult parse(byte[] bytes, String fileName, String declaredType) {
        String format = detectFormat(bytes, fileName);
        if (!"XML".equals(format)) {
            String type = normalizeDeclaredType(declaredType, true);
            return new ParseResult(type, format, "", "", "PENDING_EXTERNAL",
                    "PDF/OFD 原件已保存；需使用财政部工具包、税务平台或银行验签工具取得结构化结果和验证证据",
                    "", "", null, null, null, "", "", "", "", "", "CNY",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(
                    new InputSource(new ByteArrayInputStream(bytes)));
            String standardCode = standardCode(document);
            if (standardCode.isBlank()) {
                throw new BusinessException("XML 不是财政部当前支持的数电票、银行电子回单或银行电子对账单标准文件");
            }
            String inferredType = switch (standardCode) {
                case "EINV" -> "DIGITAL_INVOICE";
                case "BKER" -> "BANK_RECEIPT";
                case "BKRS" -> "BANK_STATEMENT";
                default -> throw new BusinessException("不支持的电子凭证标准：" + standardCode);
            };
            String normalizedDeclared = normalizeDeclaredType(declaredType, false);
            if (!normalizedDeclared.isBlank() && !normalizedDeclared.equals(inferredType)) {
                throw new BusinessException("所选凭证类型与 XML 标准命名空间不一致");
            }
            return switch (standardCode) {
                case "EINV" -> parseInvoice(document, format, schemaVersion(document));
                case "BKER" -> parseBankReceipt(document, format, schemaVersion(document));
                case "BKRS" -> parseBankStatement(document, format, schemaVersion(document));
                default -> throw new BusinessException("不支持的电子凭证标准");
            };
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("XML 解析失败：" + safeMessage(ex));
        }
    }

    private ParseResult parseInvoice(Document document, String format, String version) {
        String number = first(document, "InvoiceNumber", "InvoiceNo");
        String sellerCode = first(document, "SellerIdNum", "SellerTaxpayerIdentificationNumber");
        String sellerName = first(document, "SellerName");
        String buyerCode = first(document, "BuyerIdNum", "BuyerTaxpayerIdentificationNumber");
        String buyerName = first(document, "BuyerName");
        String entityCode = first(document, "UnifiedSocialCreditCodeOfAccountingEntity");
        String entityName = first(document, "NameOfAccountingEntity");
        String direction = "";
        String counterpartyCode = sellerCode;
        String counterpartyName = sellerName;
        if (StringUtils.hasText(entityCode) && entityCode.equalsIgnoreCase(sellerCode)) {
            direction = "OUTPUT";
            counterpartyCode = buyerCode;
            counterpartyName = buyerName;
        } else if (StringUtils.hasText(entityCode)) {
            direction = "INPUT";
        }
        LocalDate issueDate = date(first(document, "InvoiceDate", "DateOfIssue", "RequestTime"));
        String accountingPeriod = first(document, "AccountingPeriod", "PeriodOfUsageConfirmation");
        LocalDate from = monthStart(accountingPeriod);
        LocalDate to = from == null ? null : YearMonth.from(from).atEndOfMonth();
        return new ParseResult("DIGITAL_INVOICE", format, "EINV", version, "PARSED",
                "结构化数电票会计数据已解析；解析成功不等同税务验真",
                number, direction, issueDate, from, to, entityCode, entityName,
                counterpartyCode, counterpartyName, "", "CNY",
                decimal(document, "TotalAmWithoutTax"), decimal(document, "TotalTaxAm"),
                decimal(document, "TotalTax-includedAmount", "TotalTaxIncludedAmount"),
                count(document, "InformationOfAccountingDocumentsTuple"));
    }

    private ParseResult parseBankReceipt(Document document, String format, String version) {
        String externalNo = first(document, "IdentifyingCode", "NumberOfBankElectronicReceipt");
        LocalDate issueDate = date(first(document, "DateOfIssue", "PostingDate"));
        String period = first(document, "AccountingPeriod");
        LocalDate from = monthStart(period);
        LocalDate to = from == null ? null : YearMonth.from(from).atEndOfMonth();
        return new ParseResult("BANK_RECEIPT", format, "BKER", version, "PARSED",
                "结构化银行电子回单会计数据已解析；解析成功不等同银行验签",
                externalNo, "", issueDate, from, to,
                first(document, "UnifiedSocialCreditCodeOfAccountingEntity"),
                first(document, "NameOfAccountingEntity"), "", "", "",
                valueOr(first(document, "Currency"), "CNY"), BigDecimal.ZERO, BigDecimal.ZERO,
                decimal(document, "TransactionAmountInFigures", "AmountPayableAndReceivable"),
                count(document, "InformationOfAccountingDocumentsTuple"));
    }

    private ParseResult parseBankStatement(Document document, String format, String version) {
        String year = first(document, "YearOfBankReconciliationStatement");
        String month = first(document, "MonthOfBankReconciliationStatement");
        LocalDate from = null;
        try {
            if (StringUtils.hasText(year) && StringUtils.hasText(month)) {
                from = YearMonth.of(Integer.parseInt(year), Integer.parseInt(month)).atDay(1);
            }
        } catch (RuntimeException ignored) {
            // The malformed period is exposed as an empty period and must be resolved before linking.
        }
        LocalDate to = from == null ? null : YearMonth.from(from).atEndOfMonth();
        String accountNo = first(document, "CustomerSettlementBankAccount");
        String issuer = first(document, "IdentificationCodeOfIssuer");
        String externalNo = String.join("-", issuer, valueOr(year, "0000"), valueOr(month, "00"), accountNo);
        return new ParseResult("BANK_STATEMENT", format, "BKRS", version, "PARSED",
                "结构化银行电子对账单会计数据已解析；解析成功不等同银行验签",
                externalNo, "", to, from, to, issuer, first(document, "NameOfCustomerAccount"),
                "", "", accountNo, valueOr(first(document, "Currency"), "CNY"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                decimal(document, "AccountBalanceAtTheEndOfReconciliationCycleAmount"),
                count(document, "InformationOfReconcileDetailsTuple"));
    }

    private String standardCode(Document document) {
        NodeList nodes = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < nodes.getLength(); i++) {
            String namespace = nodes.item(i).getNamespaceURI();
            if (namespace == null) continue;
            String lower = namespace.toLowerCase(Locale.ROOT);
            if (lower.endsWith("/einv") || lower.contains("/einv/")) return "EINV";
            if (lower.endsWith("/bker") || lower.contains("/bker/")) return "BKER";
            if (lower.endsWith("/bkrs") || lower.contains("/bkrs/")) return "BKRS";
        }
        return "";
    }

    private String schemaVersion(Document document) {
        NodeList nodes = document.getElementsByTagNameNS("*", "schemaRef");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String href = element.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            if (!StringUtils.hasText(href)) href = element.getAttribute("xlink:href");
            Matcher matcher = VERSION.matcher(href);
            if (matcher.find()) return matcher.group(1);
        }
        return "UNKNOWN";
    }

    private String first(Document document, String... localNames) {
        for (String name : localNames) {
            NodeList nodes = document.getElementsByTagNameNS("*", name);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                String value = node.getTextContent();
                if (StringUtils.hasText(value)) return value.trim();
            }
        }
        return "";
    }

    private int count(Document document, String localName) {
        return document.getElementsByTagNameNS("*", localName).getLength();
    }

    private BigDecimal decimal(Document document, String... names) {
        String value = first(document, names);
        if (!StringUtils.hasText(value)) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new BusinessException("结构化电子凭证金额格式错误：" + value);
        }
    }

    private LocalDate date(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        if (normalized.length() >= 10) normalized = normalized.substring(0, 10);
        try {
            return LocalDate.parse(normalized);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private LocalDate monthStart(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return YearMonth.parse(value.trim().substring(0, Math.min(7, value.trim().length()))).atDay(1);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String detectFormat(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) throw new BusinessException("电子凭证文件不能为空");
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int index = startsWith(bytes, 0xef, 0xbb, 0xbf) ? 3 : 0;
        while (index < bytes.length && Character.isWhitespace((char) (bytes[index] & 0xff))) index++;
        if (index < bytes.length && bytes[index] == '<') return "XML";
        if (startsWithAscii(bytes, "%PDF-")) return "PDF";
        if (startsWith(bytes, 0x50, 0x4b) && lower.endsWith(".ofd")) return "OFD";
        throw new BusinessException("仅支持内容真实匹配的 XML、PDF 或 OFD 电子凭证文件");
    }

    private String normalizeDeclaredType(String value, boolean required) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if ("AUTO".equals(normalized)) normalized = "";
        if (required && normalized.isBlank()) throw new BusinessException("PDF/OFD 无法仅靠文件内容识别凭证类型，请选择数电票、银行回单或银行对账单");
        if (!normalized.isBlank() && !java.util.Set.of("DIGITAL_INVOICE", "BANK_RECEIPT", "BANK_STATEMENT").contains(normalized)) {
            throw new BusinessException("不支持的电子凭证类型：" + normalized);
        }
        return normalized;
    }

    private boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if ((bytes[i] & 0xff) != expected[i]) return false;
        return true;
    }

    private boolean startsWithAscii(byte[] bytes, String expected) {
        return startsWith(bytes, expected.chars().toArray());
    }

    private String valueOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String safeMessage(Exception ex) {
        String value = ex.getMessage();
        return StringUtils.hasText(value) ? value.replaceAll("[\\r\\n]+", " ") : ex.getClass().getSimpleName();
    }

    public record ParseResult(String credentialType, String fileFormat, String standardCode,
                              String standardVersion, String parseStatus, String parseMessage,
                              String externalNo, String directionHint, LocalDate issueDate,
                              LocalDate periodFrom, LocalDate periodTo,
                              String accountingEntityCode, String accountingEntityName,
                              String counterpartyCode, String counterpartyName,
                              String bankAccountNo, String currency,
                              BigDecimal amountExcludingTax, BigDecimal taxAmount,
                              BigDecimal totalAmount, int detailCount) {}
}
