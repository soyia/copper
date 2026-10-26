package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ComplexProductMasterService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String VIEW_PERMISSION = "product.master.complex.view";
    private static final String MANAGE_PERMISSION = "product.master.complex.manage";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ProductMasterDataService productMasterDataService;
    private final PackagingService packagingService;
    private final BusinessDataScopeService dataScopeService;

    public ComplexProductMasterService(
            JdbcTemplate jdbc, ObjectMapper objectMapper,
            ProductMasterDataService productMasterDataService,
            PackagingService packagingService,
            BusinessDataScopeService dataScopeService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.productMasterDataService = productMasterDataService;
        this.packagingService = packagingService;
        this.dataScopeService = dataScopeService;
    }

    public List<ProductMasterDataDtos.PackagingVariant> packagingVariants(Long productId) {
        requireViewPermission();
        requireProduct(productId);
        List<ProductMasterDataDtos.PackagingVariant> rows = jdbc.query("""
                select v.id,v.product_id,v.variant_code,v.name,v.current_revision_id,
                       v.current_revision_no,v.status,r.package_unit,r.conversion_mode,
                       r.fixed_base_quantity,r.reference_base_quantity,r.inner_spec,
                       r.net_weight,r.gross_weight,r.tare_weight,r.length_mm,r.width_mm,r.height_mm,
                       r.barcode,r.supplier_id,r.source_document_id,
                       r.source_document_revision_id,r.change_reason,r.published_at
                from packaging_variant v
                left join packaging_variant_revision r on r.id=v.current_revision_id
                where v.product_id=? order by v.status,v.variant_code,v.id
                """, (rs, rowNum) -> new ProductMasterDataDtos.PackagingVariant(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("variant_code"),
                rs.getString("name"), zeroToNull(rs.getLong("current_revision_id")),
                rs.getInt("current_revision_no"), rs.getString("package_unit"),
                rs.getString("conversion_mode"), rs.getBigDecimal("fixed_base_quantity"),
                rs.getBigDecimal("reference_base_quantity"), rs.getString("inner_spec"),
                rs.getBigDecimal("net_weight"), rs.getBigDecimal("gross_weight"),
                rs.getBigDecimal("tare_weight"), rs.getBigDecimal("length_mm"),
                rs.getBigDecimal("width_mm"), rs.getBigDecimal("height_mm"),
                rs.getString("barcode"), zeroToNull(rs.getLong("supplier_id")),
                zeroToNull(rs.getLong("source_document_id")),
                zeroToNull(rs.getLong("source_document_revision_id")), rs.getString("status"),
                rs.getString("change_reason"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toLocalDateTime()
        ), productId);
        Set<Long> allowedSupplierIds = dataScopeService.filterMasterIds(
                "PARTNER", rows.stream()
                        .map(ProductMasterDataDtos.PackagingVariant::supplierId)
                        .filter(Objects::nonNull)
                        .toList());
        return rows.stream()
                .filter(row -> row.supplierId() == null
                        || allowedSupplierIds.contains(row.supplierId()))
                .toList();
    }

    public List<ProductMasterDataDtos.PackagingVariantRevision> packagingVariantRevisions(
            Long productId, Long variantId) {
        requireViewPermission();
        requireProduct(productId);
        assertPackagingVariantPartnerScope(productId, variantId);
        Integer count = jdbc.queryForObject("""
                select count(*) from packaging_variant where id=? and product_id=?
                """, Integer.class, variantId, productId);
        if (count == null || count == 0) throw new BusinessException("包装方案不存在");
        return jdbc.query("""
                select r.id,r.packaging_variant_id,r.product_id,r.variant_code_snapshot,
                       r.name_snapshot,r.head_status_snapshot,
                       (r.variant_code_snapshot is not null
                        and r.name_snapshot is not null
                        and r.head_status_snapshot is not null) head_snapshot_available,
                       r.revision_no,
                       r.package_unit,r.conversion_mode,r.fixed_base_quantity,
                       r.reference_base_quantity,r.inner_spec,r.net_weight,r.gross_weight,
                       r.tare_weight,r.length_mm,r.width_mm,r.height_mm,r.barcode,r.supplier_id,
                       r.source_document_id,r.source_document_revision_id,r.content_hash,
                       r.status revision_status,r.change_reason,
                       r.published_by,r.published_by_name,r.published_at,
                       r.id=v.current_revision_id current_revision
                from packaging_variant_revision r
                join packaging_variant v on v.id=r.packaging_variant_id
                where r.packaging_variant_id=? and r.product_id=?
                order by r.revision_no desc,r.id desc
                """, (rs, rowNum) -> new ProductMasterDataDtos.PackagingVariantRevision(
                rs.getLong("id"), rs.getLong("packaging_variant_id"),
                rs.getLong("product_id"), rs.getString("variant_code_snapshot"),
                rs.getString("name_snapshot"), rs.getString("head_status_snapshot"),
                rs.getBoolean("head_snapshot_available"),
                rs.getInt("revision_no"), rs.getString("package_unit"),
                rs.getString("conversion_mode"), rs.getBigDecimal("fixed_base_quantity"),
                rs.getBigDecimal("reference_base_quantity"), rs.getString("inner_spec"),
                rs.getBigDecimal("net_weight"), rs.getBigDecimal("gross_weight"),
                rs.getBigDecimal("tare_weight"), rs.getBigDecimal("length_mm"),
                rs.getBigDecimal("width_mm"), rs.getBigDecimal("height_mm"),
                rs.getString("barcode"), zeroToNull(rs.getLong("supplier_id")),
                zeroToNull(rs.getLong("source_document_id")),
                zeroToNull(rs.getLong("source_document_revision_id")),
                rs.getString("content_hash"), rs.getString("revision_status"),
                rs.getString("change_reason"), zeroToNull(rs.getLong("published_by")),
                rs.getString("published_by_name"),
                rs.getTimestamp("published_at") == null
                        ? null : rs.getTimestamp("published_at").toLocalDateTime(),
                rs.getBoolean("current_revision")
        ), variantId, productId);
    }

    @Transactional
    public ProductMasterDataDtos.PackagingVariant createPackagingVariant(
            Long productId, ProductMasterDataDtos.PackagingVariantSave request) {
        requireManagePermission();
        ProductContext product = requireProduct(productId);
        UnitRef packageUnit = requireAllowedUnit(
                product, "PACKAGING", request.packageUnit(), false, "包装单位");
        String code = StringUtils.hasText(request.variantCode())
                ? request.variantCode().trim().toUpperCase(Locale.ROOT)
                : "PKG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        long variantId = insert("""
                insert into packaging_variant(product_id,variant_code,name,status)
                values(?,?,?,?)
                """, productId, code, required(request.name(), "包装方案名称"), status(request.status()));
        publishPackagingVariant(variantId, productId, 0, request, packageUnit.canonicalName());
        return packagingVariant(productId, variantId);
    }

    @Transactional
    public ProductMasterDataDtos.PackagingVariant updatePackagingVariant(
            Long productId, Long variantId, ProductMasterDataDtos.PackagingVariantSave request) {
        requireManagePermission();
        ProductContext product = requireProduct(productId);
        assertPackagingVariantPartnerScope(productId, variantId);
        List<Map<String, Object>> heads = jdbc.queryForList("""
                select current_revision_no from packaging_variant
                where id=? and product_id=? for update
                """, variantId, productId);
        if (heads.isEmpty()) throw new BusinessException("包装方案不存在");
        int revisionNo = ((Number) heads.get(0).get("current_revision_no")).intValue();
        requireExpectedRevision(request.expectedRevisionNo(), revisionNo, "包装方案");
        UnitRef packageUnit = requireAllowedUnit(
                product, "PACKAGING", request.packageUnit(), false, "包装单位");
        jdbc.update("update packaging_variant set name=?,status=? where id=?",
                required(request.name(), "包装方案名称"), status(request.status()), variantId);
        publishPackagingVariant(
                variantId, productId, revisionNo, request, packageUnit.canonicalName());
        return packagingVariant(productId, variantId);
    }

    public List<ProductMasterDataDtos.SupplierOffer> supplierOffers(
            Long productId, Long supplierId, String status, LocalDate businessDate) {
        requireSupplierOfferViewPermission();
        requireProduct(productId);
        if (supplierId != null && supplierId > 0) {
            dataScopeService.assertMasterAllowed("PARTNER", supplierId);
        }
        String supplierNameProjection = businessDate == null
                ? "p.name" : "r.supplier_name_snapshot";
        String headStatusProjection = businessDate == null
                ? "o.status" : "r.head_status_snapshot";
        StringBuilder sql = new StringBuilder("""
                select o.id,r.supplier_id,%s supplier_name,r.product_id,
                       r.supplier_product_code,r.supplier_model,r.id current_revision_id,
                       r.revision_no current_revision_no,%s status,
                       r.purchase_unit,r.price_unit,
                       r.quoted_unit_price,r.currency_code,
                       r.price_includes_tax,r.tax_rate,r.minimum_order_quantity,r.order_multiple,
                       r.lead_time_days,r.effective_from,r.effective_to,
                       r.packaging_variant_revision_id,r.source_document_id,
                       r.source_document_revision_id,
                       cast(r.commercial_terms_json as char) commercial_terms_json,
                       r.change_reason,r.published_at
                from supplier_product_offer o
                join partner p on p.id=o.supplier_id
                join supplier_product_offer_revision r
                  on r.supplier_product_offer_id=o.id
                where o.product_id=?
                """.formatted(supplierNameProjection, headStatusProjection));
        List<Object> args = new ArrayList<>();
        args.add(productId);
        if (supplierId != null && supplierId > 0) {
            sql.append(" and o.supplier_id=?");
            args.add(supplierId);
        }
        if (StringUtils.hasText(status)) {
            sql.append(businessDate == null
                    ? " and o.status=?" : " and r.head_status_snapshot=?");
            args.add(status(status));
        }
        if (businessDate != null) {
            sql.append("""
                     and r.status='PUBLISHED'
                     and r.effective_from<=? and (r.effective_to is null or r.effective_to>=?)
                     and not exists (
                       select 1 from supplier_product_offer_revision r2
                       where r2.supplier_product_offer_id=r.supplier_product_offer_id
                         and r2.status='PUBLISHED'
                         and r2.effective_from<=?
                         and (
                           r2.effective_from>r.effective_from
                           or (r2.effective_from=r.effective_from
                               and r2.revision_no>r.revision_no)
                         )
                     )
                    """);
            args.add(Date.valueOf(businessDate));
            args.add(Date.valueOf(businessDate));
            args.add(Date.valueOf(businessDate));
        } else {
            sql.append(" and r.id=o.current_revision_id");
        }
        sql.append(" order by o.status,o.supplier_id,o.id");
        List<ProductMasterDataDtos.SupplierOffer> rows = jdbc.query(
                sql.toString(), (rs, rowNum) -> new ProductMasterDataDtos.SupplierOffer(
                rs.getLong("id"), rs.getLong("supplier_id"), rs.getString("supplier_name"),
                rs.getLong("product_id"), rs.getString("supplier_product_code"),
                rs.getString("supplier_model"), zeroToNull(rs.getLong("current_revision_id")),
                rs.getInt("current_revision_no"), rs.getString("purchase_unit"),
                rs.getString("price_unit"), rs.getBigDecimal("quoted_unit_price"),
                rs.getString("currency_code"),
                rs.getBoolean("price_includes_tax"), rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("minimum_order_quantity"), rs.getBigDecimal("order_multiple"),
                rs.getInt("lead_time_days"), date(rs.getDate("effective_from")),
                date(rs.getDate("effective_to")),
                zeroToNull(rs.getLong("packaging_variant_revision_id")),
                zeroToNull(rs.getLong("source_document_id")),
                zeroToNull(rs.getLong("source_document_revision_id")),
                rs.getString("commercial_terms_json"), rs.getString("status"),
                rs.getString("change_reason"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toLocalDateTime()
        ), args.toArray());
        return dataScopeService.filterMasterList("PARTNER", rows, "supplierId");
    }

    @Transactional
    public ProductMasterDataDtos.SupplierOffer createSupplierOffer(
            Long productId, ProductMasterDataDtos.SupplierOfferSave request) {
        requireSupplierOfferManagePermission();
        ProductContext product = requireProduct(productId);
        requireSupplier(request.supplierId());
        jdbc.queryForList("select id from product where id=? for update", productId);
        OfferUnits units = validateOffer(product, request, null);
        long offerId = insert("""
                insert into supplier_product_offer
                (supplier_id,product_id,supplier_product_code,supplier_model,status)
                values(?,?,?,?,?)
                """, request.supplierId(), productId, text(request.supplierProductCode()),
                text(request.supplierModel()), status(request.status()));
        publishSupplierOffer(offerId, productId, 0, request, units);
        return supplierOffer(productId, offerId);
    }

    @Transactional
    public ProductMasterDataDtos.SupplierOffer updateSupplierOffer(
            Long productId, Long offerId, ProductMasterDataDtos.SupplierOfferSave request) {
        requireSupplierOfferManagePermission();
        ProductContext product = requireProduct(productId);
        requireSupplier(request.supplierId());
        jdbc.queryForList("select id from product where id=? for update", productId);
        List<Map<String, Object>> heads = jdbc.queryForList("""
                select supplier_id,current_revision_no from supplier_product_offer
                where id=? and product_id=? for update
                """, offerId, productId);
        if (heads.isEmpty()) throw new BusinessException("供应商供货档案不存在");
        int revisionNo = ((Number) heads.get(0).get("current_revision_no")).intValue();
        requireExpectedRevision(request.expectedRevisionNo(), revisionNo, "供应商供货档案");
        long existingSupplier = ((Number) heads.get(0).get("supplier_id")).longValue();
        dataScopeService.assertMasterAllowed("PARTNER", existingSupplier);
        if (existingSupplier != request.supplierId()) {
            throw new BusinessException("供应商供货档案建立后不能更换供应商");
        }
        OfferUnits units = validateOffer(product, request, offerId);
        jdbc.update("""
                update supplier_product_offer
                set supplier_product_code=?,supplier_model=?,status=? where id=?
                """, text(request.supplierProductCode()), text(request.supplierModel()),
                status(request.status()), offerId);
        publishSupplierOffer(offerId, productId, revisionNo, request, units);
        return supplierOffer(productId, offerId);
    }

    public List<ProductMasterDataDtos.SupplierOfferRevision> supplierOfferRevisions(
            Long productId, Long offerId) {
        requireSupplierOfferViewPermission();
        requireProduct(productId);
        List<Long> supplierIds = jdbc.query("""
                select supplier_id from supplier_product_offer
                where id=? and product_id=?
                """, (rs, rowNum) -> rs.getLong(1), offerId, productId);
        if (supplierIds.isEmpty()) throw new BusinessException("供应商供货档案不存在");
        dataScopeService.assertMasterAllowed("PARTNER", supplierIds.get(0));
        return jdbc.query("""
                select r.id,r.supplier_product_offer_id,r.supplier_id,
                       r.supplier_name_snapshot,r.product_id,
                       r.supplier_product_code supplier_product_code_snapshot,
                       r.supplier_model supplier_model_snapshot,
                       r.head_status_snapshot,
                       (r.supplier_name_snapshot is not null
                        and r.head_status_snapshot is not null) head_snapshot_available,
                       r.revision_no,r.purchase_unit,r.price_unit,
                       r.quoted_unit_price,r.currency_code,
                       r.price_includes_tax,r.tax_rate,r.minimum_order_quantity,r.order_multiple,
                       r.lead_time_days,r.effective_from,r.effective_to,
                       r.packaging_variant_revision_id,r.source_document_id,
                       r.source_document_revision_id,
                       cast(r.commercial_terms_json as char) commercial_terms_json,
                       r.content_hash,r.status revision_status,r.change_reason,
                       r.published_by,r.published_by_name,r.published_at,
                       r.id=o.current_revision_id current_revision
                from supplier_product_offer_revision r
                join supplier_product_offer o on o.id=r.supplier_product_offer_id
                where r.supplier_product_offer_id=? order by r.revision_no desc
                """, (rs, rowNum) -> new ProductMasterDataDtos.SupplierOfferRevision(
                rs.getLong("id"), rs.getLong("supplier_product_offer_id"),
                rs.getLong("supplier_id"), rs.getString("supplier_name_snapshot"),
                rs.getLong("product_id"), rs.getString("supplier_product_code_snapshot"),
                rs.getString("supplier_model_snapshot"), rs.getString("head_status_snapshot"),
                rs.getBoolean("head_snapshot_available"), rs.getInt("revision_no"),
                rs.getString("purchase_unit"),
                rs.getString("price_unit"), rs.getBigDecimal("quoted_unit_price"),
                rs.getString("currency_code"),
                rs.getBoolean("price_includes_tax"), rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("minimum_order_quantity"), rs.getBigDecimal("order_multiple"),
                rs.getInt("lead_time_days"), date(rs.getDate("effective_from")),
                date(rs.getDate("effective_to")),
                zeroToNull(rs.getLong("packaging_variant_revision_id")),
                zeroToNull(rs.getLong("source_document_id")),
                zeroToNull(rs.getLong("source_document_revision_id")),
                rs.getString("commercial_terms_json"), rs.getString("content_hash"),
                rs.getString("revision_status"), rs.getString("change_reason"),
                zeroToNull(rs.getLong("published_by")), rs.getString("published_by_name"),
                rs.getTimestamp("published_at").toLocalDateTime(),
                rs.getBoolean("current_revision")
        ), offerId);
    }

    public List<ProductMasterDataDtos.Port> ports(Long productId) {
        requireViewPermission();
        requireProduct(productId);
        return jdbc.query("""
                select * from product_port where product_id=?
                order by sort_no,port_no,id
                """, (rs, rowNum) -> new ProductMasterDataDtos.Port(
                rs.getLong("id"), rs.getLong("product_id"), rs.getInt("port_no"),
                rs.getString("port_role"), rs.getString("connection_type"),
                rs.getString("gender"), rs.getString("nominal_size"),
                rs.getBigDecimal("nominal_size_mm"), rs.getString("pressure_class"),
                rs.getBigDecimal("angle_degree"), rs.getString("standard_code"),
                rs.getString("material_code"), rs.getInt("sort_no"), rs.getString("status")
        ), productId);
    }

    @Transactional
    public List<ProductMasterDataDtos.Port> savePorts(
            Long productId, List<ProductMasterDataDtos.PortSave> requests) {
        requireManagePermission();
        requireProduct(productId);
        requirePendingIdentityStructure(productId);
        List<ProductMasterDataDtos.PortSave> values = requests == null ? List.of() : requests;
        Set<Integer> portNumbers = new HashSet<>();
        for (ProductMasterDataDtos.PortSave request : values) {
            if (!portNumbers.add(request.portNo())) throw new BusinessException("端口序号不能重复");
        }
        jdbc.update("delete from product_port where product_id=?", productId);
        for (ProductMasterDataDtos.PortSave request : values) {
            jdbc.update("""
                    insert into product_port
                    (product_id,port_no,port_role,connection_type,gender,nominal_size,
                     nominal_size_mm,pressure_class,angle_degree,standard_code,material_code,
                     sort_no,status)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, productId, request.portNo(), text(request.portRole()),
                    required(request.connectionType(), "连接方式"), text(request.gender()),
                    required(request.nominalSize(), "公称规格"), request.nominalSizeMm(),
                    text(request.pressureClass()), request.angleDegree(),
                    text(request.standardCode()), text(request.materialCode()),
                    request.sortNo() == null ? request.portNo() * 10 : request.sortNo(),
                    status(request.status()));
        }
        refreshStructuredIdentity(productId);
        return ports(productId);
    }

    public ProductMasterDataDtos.ConductorConstruction conductorConstruction(Long productId) {
        requireViewPermission();
        requireProduct(productId);
        List<ProductMasterDataDtos.ConductorConstruction> rows = jdbc.query("""
                select * from conductor_construction where product_id=?
                """, (rs, rowNum) -> new ProductMasterDataDtos.ConductorConstruction(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("construction_type"),
                rs.getBigDecimal("nominal_cross_section_mm2"), rs.getInt("strand_count"),
                rs.getBigDecimal("single_wire_diameter_mm"), rs.getBigDecimal("conductor_width_mm"),
                rs.getBigDecimal("conductor_thickness_mm"), rs.getString("lay_direction"),
                rs.getBigDecimal("lay_pitch_mm"), rs.getString("thermal_class"),
                rs.getString("insulation_system_code"), rs.getString("construction_signature"),
                rs.getString("status"), List.of()
        ), productId);
        if (rows.isEmpty()) return null;
        ProductMasterDataDtos.ConductorConstruction row = rows.get(0);
        List<ProductMasterDataDtos.ConductorComponent> components = jdbc.query("""
                select * from conductor_construction_component
                where conductor_construction_id=? order by layer_no,sort_no,id
                """, (rs, rowNum) -> new ProductMasterDataDtos.ConductorComponent(
                rs.getLong("id"), rs.getInt("layer_no"), rs.getString("component_type"),
                rs.getString("material_code"), rs.getInt("strand_count"),
                rs.getBigDecimal("diameter_mm"), rs.getBigDecimal("thickness_mm"),
                rs.getString("lay_direction"), rs.getBigDecimal("lay_pitch_mm"),
                rs.getInt("sort_no"), rs.getString("status")
        ), row.id());
        return new ProductMasterDataDtos.ConductorConstruction(
                row.id(), row.productId(), row.constructionType(), row.nominalCrossSectionMm2(),
                row.strandCount(), row.singleWireDiameterMm(), row.conductorWidthMm(),
                row.conductorThicknessMm(), row.layDirection(), row.layPitchMm(),
                row.thermalClass(), row.insulationSystemCode(), row.constructionSignature(),
                row.status(), components
        );
    }

    @Transactional
    public ProductMasterDataDtos.ConductorConstruction saveConductorConstruction(
            Long productId, ProductMasterDataDtos.ConductorConstructionSave request) {
        requireManagePermission();
        requireProduct(productId);
        requirePendingIdentityStructure(productId);
        List<ProductMasterDataDtos.ConductorComponentSave> components =
                request.components() == null ? List.of() : request.components().stream()
                        .sorted(Comparator
                                .comparing(ProductMasterDataDtos.ConductorComponentSave::layerNo)
                                .thenComparing(item -> item.sortNo() == null ? Integer.MAX_VALUE : item.sortNo())
                                .thenComparing(item -> canonicalStructuredText(item.componentType()))
                                .thenComparing(item -> canonicalStructuredText(item.materialCode()))
                                .thenComparing(item -> canonicalStructuredNumber(item.diameterMm()))
                                .thenComparing(item -> canonicalStructuredNumber(item.thicknessMm())))
                        .toList();
        Map<String, Object> signatureSource = new LinkedHashMap<>();
        signatureSource.put("constructionType", canonicalStructuredText(request.constructionType()));
        signatureSource.put("nominalCrossSectionMm2", canonicalStructuredNumber(request.nominalCrossSectionMm2()));
        signatureSource.put("strandCount", request.strandCount() == null ? 0 : request.strandCount());
        signatureSource.put("singleWireDiameterMm", canonicalStructuredNumber(request.singleWireDiameterMm()));
        signatureSource.put("conductorWidthMm", canonicalStructuredNumber(request.conductorWidthMm()));
        signatureSource.put("conductorThicknessMm", canonicalStructuredNumber(request.conductorThicknessMm()));
        signatureSource.put("layDirection", canonicalStructuredText(request.layDirection()));
        signatureSource.put("layPitchMm", canonicalStructuredNumber(request.layPitchMm()));
        signatureSource.put("thermalClass", canonicalStructuredText(request.thermalClass()));
        signatureSource.put("insulationSystemCode", canonicalStructuredText(request.insulationSystemCode()));
        signatureSource.put("components", components.stream().map(component -> {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("layerNo", component.layerNo());
            canonical.put("sortNo", component.sortNo() == null ? 0 : component.sortNo());
            canonical.put("componentType", canonicalStructuredText(component.componentType()));
            canonical.put("materialCode", canonicalStructuredText(component.materialCode()));
            canonical.put("strandCount", component.strandCount() == null ? 0 : component.strandCount());
            canonical.put("diameterMm", canonicalStructuredNumber(component.diameterMm()));
            canonical.put("thicknessMm", canonicalStructuredNumber(component.thicknessMm()));
            canonical.put("layDirection", canonicalStructuredText(component.layDirection()));
            canonical.put("layPitchMm", canonicalStructuredNumber(component.layPitchMm()));
            canonical.put("status", status(component.status()));
            return canonical;
        }).toList());
        String signature = sha256(json(signatureSource));
        jdbc.update("""
                insert into conductor_construction
                (product_id,construction_type,nominal_cross_section_mm2,strand_count,
                 single_wire_diameter_mm,conductor_width_mm,conductor_thickness_mm,
                 lay_direction,lay_pitch_mm,thermal_class,insulation_system_code,
                 construction_signature,status)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                on duplicate key update construction_type=values(construction_type),
                  nominal_cross_section_mm2=values(nominal_cross_section_mm2),
                  strand_count=values(strand_count),single_wire_diameter_mm=values(single_wire_diameter_mm),
                  conductor_width_mm=values(conductor_width_mm),
                  conductor_thickness_mm=values(conductor_thickness_mm),
                  lay_direction=values(lay_direction),lay_pitch_mm=values(lay_pitch_mm),
                  thermal_class=values(thermal_class),insulation_system_code=values(insulation_system_code),
                  construction_signature=values(construction_signature),status=values(status)
                """, productId, request.constructionType().trim().toUpperCase(Locale.ROOT),
                request.nominalCrossSectionMm2(), request.strandCount() == null ? 0 : request.strandCount(),
                request.singleWireDiameterMm(), request.conductorWidthMm(),
                request.conductorThicknessMm(), text(request.layDirection()).toUpperCase(Locale.ROOT),
                request.layPitchMm(), text(request.thermalClass()),
                text(request.insulationSystemCode()), signature, status(request.status()));
        Long constructionId = jdbc.queryForObject(
                "select id from conductor_construction where product_id=?", Long.class, productId);
        jdbc.update("delete from conductor_construction_component where conductor_construction_id=?", constructionId);
        int defaultSort = 10;
        for (ProductMasterDataDtos.ConductorComponentSave component : components) {
            jdbc.update("""
                    insert into conductor_construction_component
                    (conductor_construction_id,layer_no,component_type,material_code,strand_count,
                     diameter_mm,thickness_mm,lay_direction,lay_pitch_mm,sort_no,status)
                    values(?,?,?,?,?,?,?,?,?,?,?)
                    """, constructionId, component.layerNo(), component.componentType().trim().toUpperCase(Locale.ROOT),
                    text(component.materialCode()), component.strandCount() == null ? 0 : component.strandCount(),
                    component.diameterMm(), component.thicknessMm(),
                    text(component.layDirection()).toUpperCase(Locale.ROOT), component.layPitchMm(),
                    component.sortNo() == null ? defaultSort : component.sortNo(), status(component.status()));
            defaultSort += 10;
        }
        refreshStructuredIdentity(productId);
        return conductorConstruction(productId);
    }

    public List<ProductMasterDataDtos.TechnicalSpec> technicalSpecs(Long productId) {
        requireViewPermission();
        requireProduct(productId);
        List<ProductMasterDataDtos.TechnicalSpec> specs = jdbc.query("""
                select * from product_technical_spec_revision
                where product_id=? order by revision_no desc
                """, (rs, rowNum) -> new ProductMasterDataDtos.TechnicalSpec(
                rs.getLong("id"), rs.getLong("product_id"), rs.getInt("revision_no"),
                rs.getString("standard_code"), date(rs.getDate("effective_from")),
                date(rs.getDate("effective_to")), zeroToNull(rs.getLong("source_document_id")),
                zeroToNull(rs.getLong("source_document_revision_id")),
                rs.getString("status"), rs.getString("change_reason"),
                rs.getTimestamp("published_at").toLocalDateTime(), List.of()
        ), productId);
        return specs.stream().map(spec -> new ProductMasterDataDtos.TechnicalSpec(
                spec.id(), spec.productId(), spec.revisionNo(), spec.standardCode(),
                spec.effectiveFrom(), spec.effectiveTo(), spec.sourceDocumentId(),
                spec.sourceDocumentRevisionId(), spec.status(),
                spec.changeReason(), spec.publishedAt(), technicalCharacteristics(spec.id())
        )).toList();
    }

    @Transactional
    public ProductMasterDataDtos.TechnicalSpec publishTechnicalSpec(
            Long productId, ProductMasterDataDtos.TechnicalSpecSave request) {
        requireManagePermission();
        requireProduct(productId);
        jdbc.queryForList("select id from product where id=? for update", productId);
        Integer currentRevisionNo = jdbc.queryForObject("""
                select coalesce(max(revision_no),0)
                from product_technical_spec_revision where product_id=?
                """, Integer.class, productId);
        if (request.expectedRevisionNo() == null
                || !Objects.equals(request.expectedRevisionNo(), currentRevisionNo)) {
            throw new BusinessException("技术规格已被其他人更新，请刷新后重试");
        }
        validateDates(request.effectiveFrom(), request.effectiveTo(), "技术规格");
        SourceRevision source = requireSourceRevision(
                request.sourceDocumentId(), request.sourceDocumentRevisionId());
        Set<String> codes = new HashSet<>();
        for (ProductMasterDataDtos.TechnicalCharacteristicSave item : request.characteristics()) {
            String code = item.characteristicCode().trim().toUpperCase(Locale.ROOT);
            if (!codes.add(code)) throw new BusinessException("技术特性编码不能重复");
            validateCharacteristic(item);
        }
        int revisionNo = nextVersion(
                "select coalesce(max(revision_no),0)+1 from product_technical_spec_revision where product_id=?",
                productId);
        SecurityContext.Principal actor = SecurityContext.require();
        String hash = sha256(json(request));
        long revisionId = insert("""
                insert into product_technical_spec_revision
                (product_id,revision_no,standard_code,effective_from,effective_to,source_document_id,
                 source_document_revision_id,content_hash,change_reason,status,published_by,published_by_name)
                values(?,?,?,?,?,?,?,?,?, 'PUBLISHED',?,?)
                """, productId, revisionNo, text(request.standardCode()),
                Date.valueOf(request.effectiveFrom()), sqlDate(request.effectiveTo()),
                source.documentId(), source.revisionId(), hash,
                required(request.changeReason(), "变更原因"),
                actor.userId(), actor.employeeName());
        int sort = 10;
        for (ProductMasterDataDtos.TechnicalCharacteristicSave item : request.characteristics()) {
            jdbc.update("""
                    insert into product_technical_characteristic
                    (technical_spec_revision_id,characteristic_code,name,value_type,target_text,
                     minimum_value,target_value,maximum_value,unit_name,test_method,test_condition,
                     is_batch_required,sort_no)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, revisionId, item.characteristicCode().trim().toUpperCase(Locale.ROOT),
                    required(item.name(), "技术特性名称"), characteristicType(item.valueType()),
                    text(item.targetText()), item.minimumValue(), item.targetValue(),
                    item.maximumValue(), text(item.unitName()), text(item.testMethod()),
                    text(item.testCondition()), bool(item.batchRequired()),
                    item.sortNo() == null ? sort : item.sortNo());
            sort += 10;
        }
        return technicalSpecs(productId).stream()
                .filter(item -> Objects.equals(item.id(), revisionId)).findFirst().orElseThrow();
    }

    public List<ProductMasterDataDtos.QualityCertificate> qualityCertificates(Long batchId) {
        requireQualityViewPermission();
        requireBatch(batchId);
        List<ProductMasterDataDtos.QualityCertificate> rows = jdbc.query("""
                select * from inventory_batch_quality_certificate
                where inventory_batch_id=? order by id desc
                """, (rs, rowNum) -> new ProductMasterDataDtos.QualityCertificate(
                rs.getLong("id"), rs.getLong("inventory_batch_id"), rs.getString("certificate_no"),
                rs.getString("certificate_type"), zeroToNull(rs.getLong("supplier_id")),
                rs.getString("manufacturer_name"), rs.getString("manufacturer_batch_no"),
                rs.getString("heat_no"), rs.getString("coil_no"), date(rs.getDate("issue_date")),
                zeroToNull(rs.getLong("source_document_id")),
                zeroToNull(rs.getLong("source_document_revision_id")),
                zeroToNull(rs.getLong("file_archive_id")),
                rs.getString("verification_status"), rs.getString("status"),
                rs.getInt("lock_version"), rs.getString("review_comment"),
                zeroToNull(rs.getLong("verified_by")), rs.getString("verified_by_name"),
                rs.getTimestamp("verified_at") == null
                        ? null : rs.getTimestamp("verified_at").toLocalDateTime(),
                List.of()
        ), batchId);
        boolean fullPartnerScope = dataScopeService.hasFullMasterScope("PARTNER");
        Set<Long> allowedSupplierIds = dataScopeService.filterMasterIds(
                "PARTNER", rows.stream()
                        .map(ProductMasterDataDtos.QualityCertificate::supplierId)
                        .filter(Objects::nonNull)
                        .toList());
        return rows.stream().map(item -> new ProductMasterDataDtos.QualityCertificate(
                item.id(), item.inventoryBatchId(), item.certificateNo(), item.certificateType(),
                item.supplierId() != null && allowedSupplierIds.contains(item.supplierId())
                        ? item.supplierId() : null,
                fullPartnerScope
                        || item.supplierId() != null && allowedSupplierIds.contains(item.supplierId())
                        ? item.manufacturerName() : null,
                item.manufacturerBatchNo(), item.heatNo(),
                item.coilNo(), item.issueDate(), item.sourceDocumentId(),
                item.sourceDocumentRevisionId(), item.fileArchiveId(),
                item.verificationStatus(), item.status(), item.lockVersion(), item.reviewComment(),
                item.reviewedBy(), item.reviewedByName(), item.reviewedAt(),
                qualityResults(item.id())
        )).toList();
    }

    @Transactional
    public ProductMasterDataDtos.QualityCertificate saveQualityCertificate(
            Long batchId, Long certificateId, ProductMasterDataDtos.QualityCertificateSave request) {
        requireQualityManagePermission();
        BatchContext batch = requireBatch(batchId);
        if (!"ENABLED".equals(status(request.status()))) {
            throw new BusinessException("质保书档案不能手工停用；发现错误请执行作废并保留审计历史");
        }
        if (request.supplierId() != null && request.supplierId() > 0) requireSupplier(request.supplierId());
        if (certificateId != null && certificateId > 0) {
            assertQualityCertificatePartnerScope(batchId, certificateId);
        }
        SourceRevision source = requireSourceRevision(
                request.sourceDocumentId(), request.sourceDocumentRevisionId());
        if (request.fileArchiveId() != null && request.fileArchiveId() > 0) {
            throw new BusinessException("质保书附件上传尚未开放，不能接受未经归属校验的附件ID");
        }
        List<QualityResultPrepared> results = validateQualityResults(batch, request.results());
        long id;
        if (certificateId == null || certificateId <= 0) {
            id = insert("""
                    insert into inventory_batch_quality_certificate
                    (inventory_batch_id,certificate_no,certificate_type,supplier_id,manufacturer_name,
                     manufacturer_batch_no,heat_no,coil_no,issue_date,source_document_id,
                     source_document_revision_id,
                     file_archive_id,verification_status,status)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?)
                    """, batchId, request.certificateNo().trim(), certificateType(request.certificateType()),
                    id(request.supplierId()), text(request.manufacturerName()),
                    text(request.manufacturerBatchNo()), text(request.heatNo()), text(request.coilNo()),
                    sqlDate(request.issueDate()), source.documentId(), source.revisionId(),
                    0, status(request.status()));
        } else {
            if (request.expectedLockVersion() == null) {
                throw new BusinessException("质保书版本缺失，请刷新后重试");
            }
            List<Map<String, Object>> current = jdbc.queryForList("""
                    select verification_status,lock_version
                    from inventory_batch_quality_certificate
                    where id=? and inventory_batch_id=? for update
                    """, certificateId, batchId);
            if (current.isEmpty()) throw new BusinessException("批次质保书不存在");
            if (!"PENDING".equals(String.valueOf(current.get(0).get("verification_status")))) {
                throw new BusinessException("已复核、已驳回或已作废质保书不可原地修改；请作废后新建");
            }
            if (((Number) current.get(0).get("lock_version")).intValue()
                    != request.expectedLockVersion()) {
                throw new BusinessException("质保书已被其他人更新，请刷新后重试");
            }
            int updated = jdbc.update("""
                    update inventory_batch_quality_certificate
                    set certificate_no=?,certificate_type=?,supplier_id=?,manufacturer_name=?,
                        manufacturer_batch_no=?,heat_no=?,coil_no=?,issue_date=?,source_document_id=?,
                        source_document_revision_id=?,file_archive_id=0,status=?,
                        lock_version=lock_version+1
                    where id=? and inventory_batch_id=? and lock_version=?
                    """, request.certificateNo().trim(), certificateType(request.certificateType()),
                    id(request.supplierId()), text(request.manufacturerName()),
                    text(request.manufacturerBatchNo()), text(request.heatNo()), text(request.coilNo()),
                    sqlDate(request.issueDate()), source.documentId(), source.revisionId(),
                    status(request.status()), certificateId, batchId, request.expectedLockVersion());
            if (updated != 1) throw new BusinessException("质保书已被其他人更新，请刷新后重试");
            id = certificateId;
            jdbc.update("delete from inventory_batch_quality_result where certificate_id=?", id);
        }
        for (QualityResultPrepared result : results) {
            jdbc.update("""
                    insert into inventory_batch_quality_result
                    (certificate_id,technical_characteristic_id,characteristic_code,measured_text,
                     measured_value,unit_name,conclusion,test_method_snapshot,tested_at)
                    values(?,?,?,?,?,?,?,?,?)
                    """, id, result.technicalCharacteristicId(),
                    result.characteristicCode(), result.measuredText(), result.measuredValue(),
                    result.unitName(), result.conclusion(), result.testMethodSnapshot(),
                    result.testedAt() == null ? null : java.sql.Timestamp.valueOf(result.testedAt()));
        }
        return qualityCertificates(batchId).stream()
                .filter(item -> Objects.equals(item.id(), id)).findFirst().orElseThrow();
    }

    @Transactional
    public ProductMasterDataDtos.QualityCertificate reviewQualityCertificate(
            Long batchId, Long certificateId,
            ProductMasterDataDtos.QualityCertificateReviewSave request) {
        SecurityContext.Principal actor = requireQualityManagePermission();
        requireBatch(batchId);
        assertQualityCertificatePartnerScope(batchId, certificateId);
        String next = required(request.reviewStatus(), "复核结果").toUpperCase(Locale.ROOT);
        if (!Set.of("VERIFIED", "REJECTED", "VOID").contains(next)) {
            throw new BusinessException("质保书复核结果只支持VERIFIED、REJECTED或VOID");
        }
        String comment = text(request.reviewComment());
        if (Set.of("REJECTED", "VOID").contains(next) && !StringUtils.hasText(comment)) {
            throw new BusinessException("驳回或作废质保书必须填写原因");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select verification_status,status,lock_version
                from inventory_batch_quality_certificate
                where id=? and inventory_batch_id=? for update
                """, certificateId, batchId);
        if (rows.isEmpty()) throw new BusinessException("批次质保书不存在");
        if (!"ENABLED".equals(String.valueOf(rows.get(0).get("status")))) {
            throw new BusinessException("已停用或已作废质保书不能再复核");
        }
        String previous = String.valueOf(rows.get(0).get("verification_status"));
        int version = ((Number) rows.get(0).get("lock_version")).intValue();
        if (version != request.expectedLockVersion()) {
            throw new BusinessException("质保书已被其他人更新，请刷新后重试");
        }
        boolean validTransition = "PENDING".equals(previous)
                ? Set.of("VERIFIED", "REJECTED", "VOID").contains(next)
                : Set.of("VERIFIED", "REJECTED").contains(previous) && "VOID".equals(next);
        if (!validTransition) {
            throw new BusinessException("当前质保书状态不能执行该复核动作");
        }
        jdbc.update("""
                insert into inventory_batch_quality_certificate_review
                (certificate_id,previous_status,review_status,review_comment,
                 reviewed_by,reviewed_by_name)
                values(?,?,?,?,?,?)
                """, certificateId, previous, next, comment,
                actor.userId(), actor.employeeName());
        int updated = jdbc.update("""
                update inventory_batch_quality_certificate
                set verification_status=?,status=?,review_comment=?,verified_by=?,
                    verified_by_name=?,verified_at=current_timestamp(6),
                    lock_version=lock_version+1
                where id=? and inventory_batch_id=? and lock_version=?
                """, next, "VOID".equals(next) ? "DISABLED" : "ENABLED",
                comment, actor.userId(), actor.employeeName(),
                certificateId, batchId, request.expectedLockVersion());
        if (updated != 1) throw new BusinessException("质保书已被其他人更新，请刷新后重试");
        return qualityCertificates(batchId).stream()
                .filter(item -> Objects.equals(item.id(), certificateId))
                .findFirst().orElseThrow();
    }

    public List<ProductMasterDataDtos.QualityCertificateReviewHistory>
            qualityCertificateReviewHistory(Long batchId, Long certificateId) {
        requireQualityViewPermission();
        requireBatch(batchId);
        assertQualityCertificatePartnerScope(batchId, certificateId);
        Integer count = jdbc.queryForObject("""
                select count(*) from inventory_batch_quality_certificate
                where id=? and inventory_batch_id=?
                """, Integer.class, certificateId, batchId);
        if (count == null || count == 0) throw new BusinessException("批次质保书不存在");
        return jdbc.query("""
                select id,certificate_id,previous_status,review_status,review_comment,
                       reviewed_by,reviewed_by_name,reviewed_at
                from inventory_batch_quality_certificate_review
                where certificate_id=?
                order by reviewed_at desc,id desc
                """, (rs, rowNum) -> new ProductMasterDataDtos.QualityCertificateReviewHistory(
                rs.getLong("id"), rs.getLong("certificate_id"),
                rs.getString("previous_status"), rs.getString("review_status"),
                rs.getString("review_comment"), zeroToNull(rs.getLong("reviewed_by")),
                rs.getString("reviewed_by_name"),
                rs.getTimestamp("reviewed_at").toLocalDateTime()
        ), certificateId);
    }

    public ProductMasterDataDtos.Bom bom(Long parentProductId) {
        requireViewPermission();
        requireProduct(parentProductId);
        List<ProductMasterDataDtos.Bom> rows = jdbc.query("""
                select b.id,b.parent_product_id,b.bom_code,b.name,
                       r.id selected_revision_id,r.revision_no,b.status,
                       r.output_quantity,r.output_unit,
                       r.output_base_quantity,r.output_base_unit,r.output_effective_revision_id,
                       cast(r.output_conversion_snapshot_json as char) output_conversion_snapshot_json,
                       r.scrap_rate,
                       r.effective_from,r.effective_to,r.change_reason,r.published_at
                from product_bom b
                left join product_bom_revision r on r.id=b.current_revision_id
                where b.parent_product_id=?
                """, bomMapper(), parentProductId);
        if (rows.isEmpty()) return null;
        return withBomItems(rows.get(0));
    }

    public List<ProductMasterDataDtos.BomRevision> bomRevisions(Long parentProductId) {
        requireViewPermission();
        requireProduct(parentProductId);
        return jdbc.query("""
                select r.id,r.bom_id,r.parent_product_id,r.bom_code_snapshot,
                       r.name_snapshot,r.head_status_snapshot,
                       (r.bom_code_snapshot is not null
                        and r.name_snapshot is not null
                        and r.head_status_snapshot is not null) head_snapshot_available,
                       r.revision_no,
                       r.output_quantity,r.output_unit,
                       r.output_base_quantity,r.output_base_unit,r.output_effective_revision_id,
                       cast(r.output_conversion_snapshot_json as char) output_conversion_snapshot_json,
                       r.scrap_rate,r.effective_from,r.effective_to,r.content_hash,
                       r.status revision_status,r.change_reason,
                       r.published_by,r.published_by_name,r.published_at,
                       r.id=b.current_revision_id current_revision
                from product_bom b
                join product_bom_revision r on r.bom_id=b.id
                where b.parent_product_id=?
                order by r.revision_no desc,r.id desc
                """, bomRevisionMapper(), parentProductId).stream()
                .map(this::withBomRevisionItems).toList();
    }

    public ProductMasterDataDtos.Bom effectiveBom(
            Long parentProductId, LocalDate businessDate) {
        requireViewPermission();
        requireProduct(parentProductId);
        LocalDate asOf = Objects.requireNonNull(businessDate, "业务日期不能为空");
        List<ProductMasterDataDtos.Bom> rows = jdbc.query("""
                select b.id,b.parent_product_id,
                       r.bom_code_snapshot bom_code,r.name_snapshot name,
                       r.id selected_revision_id,r.revision_no,
                       r.head_status_snapshot status,
                       r.output_quantity,r.output_unit,
                       r.output_base_quantity,r.output_base_unit,r.output_effective_revision_id,
                       cast(r.output_conversion_snapshot_json as char) output_conversion_snapshot_json,
                       r.scrap_rate,
                       r.effective_from,r.effective_to,r.change_reason,r.published_at
                from product_bom b
                join product_bom_revision r on r.bom_id=b.id
                where b.parent_product_id=?
                  and r.head_status_snapshot='ENABLED'
                  and r.status='PUBLISHED' and r.effective_from<=?
                  and (r.effective_to is null or r.effective_to>=?)
                  and not exists (
                    select 1 from product_bom_revision r2
                    where r2.bom_id=r.bom_id and r2.status='PUBLISHED'
                      and r2.effective_from<=?
                      and (r2.effective_from>r.effective_from
                           or (r2.effective_from=r.effective_from
                               and r2.revision_no>r.revision_no))
                  )
                """, bomMapper(), parentProductId, Date.valueOf(asOf),
                Date.valueOf(asOf), Date.valueOf(asOf));
        if (rows.isEmpty()) return null;
        if (rows.size() > 1) {
            throw new BusinessException("同一商品和业务日期存在多份生效BOM，请修复版本生效区间");
        }
        return withBomItems(rows.get(0));
    }

    @Transactional
    public ProductMasterDataDtos.Bom publishBom(
            Long parentProductId, ProductMasterDataDtos.BomSave request) {
        requireManagePermission();
        requireProduct(parentProductId);
        validateDates(request.effectiveFrom(), request.effectiveTo(), "BOM");
        PackagingDtos.Plan outputPlan = bomUnitPlan(
                parentProductId, request.outputQuantity(), request.outputUnit(), "SALES", "产成品单位");
        Set<Long> componentIds = new HashSet<>();
        Map<Long, PackagingDtos.Plan> componentPlans = new LinkedHashMap<>();
        for (ProductMasterDataDtos.BomItemSave item : request.items()) {
            if (Objects.equals(item.componentProductId(), parentProductId)) {
                throw new BusinessException("BOM不能把产成品自身作为组件");
            }
            requireProduct(item.componentProductId());
            if (!componentIds.add(item.componentProductId())) {
                throw new BusinessException("BOM组件商品不能重复");
            }
            componentPlans.put(item.componentProductId(), bomUnitPlan(
                    item.componentProductId(), item.quantity(), item.unit(),
                    "PURCHASE", "组件单位"));
        }
        List<Long> affectedProducts = new ArrayList<>(componentIds);
        affectedProducts.add(parentProductId);
        affectedProducts.sort(Long::compareTo);
        String productLocks = String.join(",",
                java.util.Collections.nCopies(affectedProducts.size(), "?"));
        jdbc.queryForList("""
                select id from product where id in (%s) order by id for update
                """.formatted(productLocks), affectedProducts.toArray());
        jdbc.queryForList("""
                select id from product_bom order by parent_product_id,id for update
                """);
        validateBomAcyclic(
                parentProductId, componentIds, status(request.status()),
                request.effectiveFrom(), request.effectiveTo());
        List<Map<String, Object>> heads = jdbc.queryForList("""
                select id,current_revision_no from product_bom where parent_product_id=? for update
                """, parentProductId);
        long bomId;
        int currentRevision;
        if (heads.isEmpty()) {
            if (request.expectedRevisionNo() == null || request.expectedRevisionNo() != 0) {
                throw new BusinessException("BOM已被其他人更新，请刷新后重试");
            }
            String code = StringUtils.hasText(request.bomCode())
                    ? request.bomCode().trim().toUpperCase(Locale.ROOT)
                    : "BOM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
            bomId = insert("""
                    insert into product_bom(parent_product_id,bom_code,name,status)
                    values(?,?,?,?)
                    """, parentProductId, code, request.name().trim(), status(request.status()));
            currentRevision = 0;
        } else {
            bomId = ((Number) heads.get(0).get("id")).longValue();
            currentRevision = ((Number) heads.get(0).get("current_revision_no")).intValue();
            if (request.expectedRevisionNo() == null
                    || request.expectedRevisionNo() != currentRevision) {
                throw new BusinessException("BOM已被其他人更新，请刷新后重试");
            }
            jdbc.update("update product_bom set name=?,status=? where id=?",
                    request.name().trim(), status(request.status()), bomId);
        }
        int revisionNo = currentRevision + 1;
        SecurityContext.Principal actor = SecurityContext.require();
        Map<String, Object> head = jdbc.queryForMap("""
                select bom_code,name,status from product_bom where id=?
                """, bomId);
        long revisionId = insert("""
                insert into product_bom_revision
                (bom_id,parent_product_id,bom_code_snapshot,name_snapshot,
                 head_status_snapshot,revision_no,output_quantity,output_unit,
                 output_base_quantity,output_base_unit,output_effective_revision_id,
                 output_conversion_snapshot_json,scrap_rate,
                 effective_from,effective_to,content_hash,change_reason,status,published_by,published_by_name)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PUBLISHED',?,?)
                """, bomId, parentProductId, String.valueOf(head.get("bom_code")),
                String.valueOf(head.get("name")), String.valueOf(head.get("status")),
                revisionNo, request.outputQuantity(),
                outputPlan.requestedUnit(), outputPlan.baseQuantity(), outputPlan.baseUnit(),
                id(outputPlan.effectiveRevisionId()), outputPlan.snapshotJson(),
                rate(request.scrapRate()), Date.valueOf(request.effectiveFrom()),
                sqlDate(request.effectiveTo()),
                sha256(json(Map.of(
                        "request", request,
                        "outputSnapshot", outputPlan.snapshotHash(),
                        "componentSnapshots", componentPlans.values().stream()
                                .map(PackagingDtos.Plan::snapshotHash).toList()
                ))),
                request.changeReason().trim(), actor.userId(), actor.employeeName());
        int sort = 10;
        for (ProductMasterDataDtos.BomItemSave item : request.items()) {
            PackagingDtos.Plan componentPlan = componentPlans.get(item.componentProductId());
            jdbc.update("""
                    insert into product_bom_item
                    (bom_revision_id,component_product_id,quantity,unit,base_quantity,base_unit,
                     packaging_effective_revision_id,conversion_snapshot_json,
                     scrap_rate,issue_method,sort_no)
                    values(?,?,?,?,?,?,?,?,?,?,?)
                    """, revisionId, item.componentProductId(), item.quantity(),
                    componentPlan.requestedUnit(), componentPlan.baseQuantity(), componentPlan.baseUnit(),
                    id(componentPlan.effectiveRevisionId()), componentPlan.snapshotJson(),
                    rate(item.scrapRate()), issueMethod(item.issueMethod()),
                    item.sortNo() == null ? sort : item.sortNo());
            sort += 10;
        }
        jdbc.update("""
                update product_bom
                set current_revision_id=?,current_revision_no=?,lock_version=lock_version+1 where id=?
                """, revisionId, revisionNo, bomId);
        return bom(parentProductId);
    }

    private void validateBomAcyclic(
            Long parentProductId, Set<Long> componentIds, String resultingHeadStatus,
            LocalDate candidateEffectiveFrom, LocalDate candidateEffectiveTo) {
        if (!"ENABLED".equals(resultingHeadStatus)) return;

        Map<Long, String> headStatuses = new HashMap<>();
        Map<Long, BomTemporalRevision> revisionById = new LinkedHashMap<>();
        Set<LocalDate> criticalDates = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select b.parent_product_id,b.status,r.id revision_id,r.revision_no,
                       r.effective_from,r.effective_to,i.component_product_id
                from product_bom b
                join product_bom_revision r on r.bom_id=b.id and r.status='PUBLISHED'
                left join product_bom_item i on i.bom_revision_id=r.id
                """)) {
            long parentId = ((Number) row.get("parent_product_id")).longValue();
            long revisionId = ((Number) row.get("revision_id")).longValue();
            LocalDate effectiveFrom = sqlLocalDate(row.get("effective_from"));
            LocalDate effectiveTo = nullableSqlLocalDate(row.get("effective_to"));
            headStatuses.put(parentId, String.valueOf(row.get("status")));
            BomTemporalRevision revision = revisionById.computeIfAbsent(
                    revisionId,
                    ignored -> new BomTemporalRevision(
                            revisionId, parentId,
                            ((Number) row.get("revision_no")).intValue(),
                            effectiveFrom, effectiveTo, new HashSet<>(), false));
            Object component = row.get("component_product_id");
            if (component instanceof Number number) {
                revision.componentIds().add(number.longValue());
            }
            addCriticalDate(criticalDates, effectiveFrom);
            addDayAfter(criticalDates, effectiveTo);
        }

        headStatuses.put(parentProductId, resultingHeadStatus);
        revisionById.put(Long.MIN_VALUE, new BomTemporalRevision(
                Long.MIN_VALUE, parentProductId, Integer.MAX_VALUE,
                candidateEffectiveFrom, candidateEffectiveTo,
                new HashSet<>(componentIds), true));
        addCriticalDate(criticalDates, candidateEffectiveFrom);
        addDayAfter(criticalDates, candidateEffectiveTo);

        Map<Long, List<BomTemporalRevision>> revisionsByParent = new HashMap<>();
        for (BomTemporalRevision revision : revisionById.values()) {
            revisionsByParent.computeIfAbsent(
                    revision.parentProductId(), ignored -> new ArrayList<>()).add(revision);
        }

        Comparator<BomTemporalRevision> newestApplicable = Comparator
                .comparing(BomTemporalRevision::effectiveFrom)
                .thenComparingInt(BomTemporalRevision::revisionNo);
        for (LocalDate businessDate : criticalDates.stream().sorted().toList()) {
            Map<Long, Set<Long>> graph = new HashMap<>();
            for (Map.Entry<Long, List<BomTemporalRevision>> entry : revisionsByParent.entrySet()) {
                if (!"ENABLED".equals(headStatuses.get(entry.getKey()))) continue;
                BomTemporalRevision selected = entry.getValue().stream()
                        .filter(revision -> !revision.effectiveFrom().isAfter(businessDate))
                        .max(newestApplicable)
                        .orElse(null);
                if (selected == null
                        || (selected.effectiveTo() != null
                            && selected.effectiveTo().isBefore(businessDate))) {
                    continue;
                }
                graph.put(entry.getKey(), selected.componentIds());
            }
            for (Long componentId : graph.getOrDefault(parentProductId, Set.of())) {
                if (reachesProduct(graph, componentId, parentProductId, new HashSet<>())) {
                    throw new BusinessException(
                            "BOM在业务日期“" + businessDate + "”存在传递循环：组件“"
                                    + componentId + "”会回到产成品“"
                                    + parentProductId + "”");
                }
            }
        }
    }

    private LocalDate sqlLocalDate(Object value) {
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof LocalDate localDate) return localDate;
        throw new BusinessException("BOM版本生效日期缺失");
    }

    private LocalDate nullableSqlLocalDate(Object value) {
        return value == null ? null : sqlLocalDate(value);
    }

    private void addCriticalDate(Set<LocalDate> dates, LocalDate value) {
        if (value != null) dates.add(value);
    }

    private void addDayAfter(Set<LocalDate> dates, LocalDate value) {
        if (value != null && !LocalDate.MAX.equals(value)) dates.add(value.plusDays(1));
    }

    private boolean reachesProduct(
            Map<Long, Set<Long>> graph, Long current, Long target, Set<Long> visited) {
        if (Objects.equals(current, target)) return true;
        if (!visited.add(current)) return false;
        for (Long next : graph.getOrDefault(current, Set.of())) {
            if (reachesProduct(graph, next, target, visited)) return true;
        }
        return false;
    }

    private void publishPackagingVariant(
            long variantId, Long productId, int currentRevision,
            ProductMasterDataDtos.PackagingVariantSave request, String packageUnit) {
        String mode = conversionMode(request.conversionMode());
        if ("FIXED".equals(mode) && positive(request.fixedBaseQuantity()) == null) {
            throw new BusinessException("固定换算包装必须填写固定基础数量");
        }
        if (!"FIXED".equals(mode) && positive(request.referenceBaseQuantity()) == null) {
            throw new BusinessException("实际重量或理论换算包装必须填写参考基础数量");
        }
        if (request.grossWeight() != null && request.netWeight() != null
                && request.grossWeight().compareTo(request.netWeight()) < 0) {
            throw new BusinessException("包装毛重不能小于净重");
        }
        if (request.supplierId() != null && request.supplierId() > 0) requireSupplier(request.supplierId());
        SourceRevision source = requireSourceRevision(
                request.sourceDocumentId(), request.sourceDocumentRevisionId());
        Map<String, Object> head = jdbc.queryForMap("""
                select variant_code,name,status from packaging_variant
                where id=? and product_id=?
                """, variantId, productId);
        SecurityContext.Principal actor = SecurityContext.require();
        int revision = currentRevision + 1;
        String hash = sha256(json(request));
        long revisionId = insert("""
                insert into packaging_variant_revision
                (packaging_variant_id,product_id,variant_code_snapshot,name_snapshot,
                 head_status_snapshot,revision_no,package_unit,conversion_mode,
                 fixed_base_quantity,reference_base_quantity,inner_spec,net_weight,gross_weight,
                 tare_weight,length_mm,width_mm,height_mm,barcode,supplier_id,source_document_id,
                 source_document_revision_id,
                content_hash,change_reason,status,published_by,published_by_name)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PUBLISHED',?,?)
                """, variantId, productId, String.valueOf(head.get("variant_code")),
                String.valueOf(head.get("name")), String.valueOf(head.get("status")),
                revision, packageUnit, mode,
                positive(request.fixedBaseQuantity()), positive(request.referenceBaseQuantity()),
                text(request.innerSpec()), request.netWeight(), request.grossWeight(),
                request.tareWeight(), request.lengthMm(), request.widthMm(), request.heightMm(),
                text(request.barcode()), id(request.supplierId()),
                source.documentId(), source.revisionId(),
                hash, request.changeReason().trim(), actor.userId(), actor.employeeName());
        jdbc.update("""
                update packaging_variant
                set current_revision_id=?,current_revision_no=?,lock_version=lock_version+1 where id=?
                """, revisionId, revision, variantId);
    }

    private void publishSupplierOffer(
            long offerId, Long productId, int currentRevision,
            ProductMasterDataDtos.SupplierOfferSave request, OfferUnits units) {
        int revision = currentRevision + 1;
        SecurityContext.Principal actor = SecurityContext.require();
        String terms = validJson(request.commercialTermsJson(), "{}");
        SourceRevision source = requireSourceRevision(
                request.sourceDocumentId(), request.sourceDocumentRevisionId());
        Map<String, Object> head = jdbc.queryForMap("""
                select o.supplier_product_code,o.supplier_model,o.status,p.name supplier_name
                from supplier_product_offer o
                join partner p on p.id=o.supplier_id
                where o.id=? and o.product_id=?
                """, offerId, productId);
        long revisionId = insert("""
                insert into supplier_product_offer_revision
                (supplier_product_offer_id,revision_no,supplier_id,supplier_name_snapshot,
                 product_id,head_status_snapshot,supplier_product_code,
                 supplier_model,purchase_unit,price_unit,quoted_unit_price,currency_code,
                 price_includes_tax,tax_rate,
                 minimum_order_quantity,order_multiple,lead_time_days,effective_from,effective_to,
                 packaging_variant_revision_id,source_document_id,source_document_revision_id,
                 commercial_terms_json,
                 content_hash,change_reason,status,published_by,published_by_name)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PUBLISHED',?,?)
                """, offerId, revision, request.supplierId(),
                String.valueOf(head.get("supplier_name")), productId,
                String.valueOf(head.get("status")),
                String.valueOf(head.get("supplier_product_code")),
                String.valueOf(head.get("supplier_model")),
                units.purchaseUnit(), units.priceUnit(), positivePrice(request.quotedUnitPrice()),
                currency(request.currencyCode()),
                bool(request.priceIncludesTax() == null || request.priceIncludesTax()),
                request.taxRate() == null ? ZERO : request.taxRate(),
                request.minimumOrderQuantity() == null ? ZERO : request.minimumOrderQuantity(),
                request.orderMultiple() == null ? ZERO : request.orderMultiple(),
                request.leadTimeDays() == null ? 0 : request.leadTimeDays(),
                Date.valueOf(request.effectiveFrom()), sqlDate(request.effectiveTo()),
                id(request.packagingVariantRevisionId()), source.documentId(), source.revisionId(), terms,
                sha256(json(request) + "|" + units.purchaseUnit() + "|" + units.priceUnit()),
                request.changeReason().trim(), actor.userId(), actor.employeeName());
        jdbc.update("""
                update supplier_product_offer
                set current_revision_id=?,current_revision_no=?,lock_version=lock_version+1 where id=?
                """, revisionId, revision, offerId);
        if (request.packagingVariantRevisionId() != null && request.packagingVariantRevisionId() > 0) {
            Long variantId = jdbc.queryForObject("""
                    select packaging_variant_id from packaging_variant_revision where id=?
                    """, Long.class, request.packagingVariantRevisionId());
            jdbc.update("""
                    insert into supplier_product_packaging_binding
                    (supplier_product_offer_id,packaging_variant_id,is_default,effective_from,effective_to,status)
                    values(?,?,1,?,?,'ENABLED')
                    on duplicate key update is_default=1,effective_to=values(effective_to),status='ENABLED'
                    """, offerId, variantId, Date.valueOf(request.effectiveFrom()), sqlDate(request.effectiveTo()));
        }
    }

    private OfferUnits validateOffer(
            ProductContext product, ProductMasterDataDtos.SupplierOfferSave request,
            Long excludedOfferId) {
        validateDates(request.effectiveFrom(), request.effectiveTo(), "供应商供货档案");
        requireSourceRevision(request.sourceDocumentId(), request.sourceDocumentRevisionId());
        UnitRef purchaseUnit = requireAllowedUnit(
                product, "PURCHASE", request.purchaseUnit(), true, "采购单位");
        UnitRef priceUnit = requireAllowedUnit(
                product, "PRICE", request.priceUnit(), true, "计价单位");
        positivePrice(request.quotedUnitPrice());
        if (request.packagingVariantRevisionId() != null && request.packagingVariantRevisionId() > 0) {
            List<Map<String, Object>> activeVariants = jdbc.queryForList("""
                    select v.id,r.supplier_id
                    from packaging_variant_revision r
                    join packaging_variant v on v.id=r.packaging_variant_id
                    where r.id=? and r.product_id=? and r.status='PUBLISHED'
                      and v.product_id=r.product_id and v.status='ENABLED'
                    for update
                    """, request.packagingVariantRevisionId(), product.id());
            if (activeVariants.isEmpty()) {
                throw new BusinessException(
                        "包装方案版本不存在、不属于当前商品，或包装方案已停用；"
                                + "停用包装不能发布或启用供应商供货档案");
            }
            Long variantSupplierId = zeroToNull(
                    ((Number) activeVariants.get(0).get("supplier_id")).longValue());
            if (variantSupplierId != null) {
                dataScopeService.assertMasterAllowed("PARTNER", variantSupplierId);
                if (!Objects.equals(variantSupplierId, request.supplierId())) {
                    throw new BusinessException("供应商专属包装方案只能用于同一供应商的供货档案");
                }
            }
        }
        try {
            packagingService.plan(product.id(), new PackagingDtos.PlanRequest(
                    BigDecimal.ONE, purchaseUnit.canonicalName(), null,
                    priceUnit.canonicalName(), "PURCHASE",
                    null, null, null, request.packagingVariantRevisionId()));
        } catch (BusinessException ex) {
            throw new BusinessException(
                    "采购单位与计价单位缺少可执行的同一计量包装换算路径：" + ex.getMessage());
        }
        if ("ENABLED".equals(status(request.status()))) {
            Integer overlap = jdbc.queryForObject("""
                    select count(*)
                    from supplier_product_offer o
                    join supplier_product_offer_revision r
                      on r.supplier_product_offer_id=o.id
                    where o.supplier_id=? and o.product_id=? and o.status='ENABLED'
                      and o.id<>? and r.status='PUBLISHED' and r.price_unit=?
                      and (? is null or r.effective_from<=?)
                      and (r.effective_to is null or r.effective_to>=?)
                    """, Integer.class, request.supplierId(), product.id(),
                    excludedOfferId == null ? 0L : excludedOfferId,
                    priceUnit.canonicalName(),
                    sqlDate(request.effectiveTo()), sqlDate(request.effectiveTo()),
                    Date.valueOf(request.effectiveFrom()));
            if (overlap != null && overlap > 0) {
                throw new BusinessException(
                        "同一供应商、商品、计价单位的启用供货档案生效期不能重叠；"
                                + "边界日期也视为同时生效，请先调整或停用旧档案");
            }
        }
        return new OfferUnits(purchaseUnit.canonicalName(), priceUnit.canonicalName());
    }

    private PackagingDtos.Plan bomUnitPlan(
            Long productId, BigDecimal quantity, String unit, String purpose, String label) {
        String requestedUnit = required(unit, label);
        boolean available = packagingService.unitOptions(productId, purpose).stream()
                .anyMatch(option -> normalizedUnit(option.name())
                        .equals(normalizedUnit(requestedUnit)));
        if (!available) {
            throw new BusinessException(label + "“" + requestedUnit
                    + "”不是该商品当前已发布的可换算单位");
        }
        return packagingService.plan(productId, new PackagingDtos.PlanRequest(
                quantity, requestedUnit, null, requestedUnit, purpose
        ));
    }

    private void validateCharacteristic(ProductMasterDataDtos.TechnicalCharacteristicSave item) {
        String type = characteristicType(item.valueType());
        if ("RANGE".equals(type) && item.minimumValue() != null && item.maximumValue() != null
                && item.minimumValue().compareTo(item.maximumValue()) > 0) {
            throw new BusinessException("技术特性“" + item.name() + "”最小值不能大于最大值");
        }
        if ("NUMBER".equals(type) && item.targetValue() == null) {
            throw new BusinessException("数值型技术特性必须填写目标值");
        }
        if ("TEXT".equals(type) && !StringUtils.hasText(item.targetText())) {
            throw new BusinessException("文本型技术特性必须填写目标内容");
        }
        if ("BOOLEAN".equals(type)
                && !Set.of("true", "false").contains(
                        canonicalStructuredText(item.targetText()))) {
            throw new BusinessException("布尔型技术特性目标值只能填写true或false");
        }
    }

    private List<ProductMasterDataDtos.TechnicalCharacteristic> technicalCharacteristics(Long revisionId) {
        return jdbc.query("""
                select * from product_technical_characteristic
                where technical_spec_revision_id=? order by sort_no,id
                """, (rs, rowNum) -> new ProductMasterDataDtos.TechnicalCharacteristic(
                rs.getLong("id"), rs.getString("characteristic_code"), rs.getString("name"),
                rs.getString("value_type"), rs.getString("target_text"),
                rs.getBigDecimal("minimum_value"), rs.getBigDecimal("target_value"),
                rs.getBigDecimal("maximum_value"), rs.getString("unit_name"),
                rs.getString("test_method"), rs.getString("test_condition"),
                rs.getBoolean("is_batch_required"), rs.getInt("sort_no")
        ), revisionId);
    }

    private List<ProductMasterDataDtos.QualityResult> qualityResults(Long certificateId) {
        return jdbc.query("""
                select * from inventory_batch_quality_result
                where certificate_id=? order by id
                """, (rs, rowNum) -> new ProductMasterDataDtos.QualityResult(
                rs.getLong("id"), zeroToNull(rs.getLong("technical_characteristic_id")),
                rs.getString("characteristic_code"), rs.getString("measured_text"),
                rs.getBigDecimal("measured_value"), rs.getString("unit_name"),
                rs.getString("conclusion"), rs.getString("test_method_snapshot"),
                rs.getTimestamp("tested_at") == null ? null : rs.getTimestamp("tested_at").toLocalDateTime()
        ), certificateId);
    }

    private List<ProductMasterDataDtos.BomItem> bomItems(Long revisionId) {
        if (revisionId == null || revisionId <= 0) return List.of();
        List<ProductMasterDataDtos.BomItem> items = jdbc.query("""
                select i.*,cast(i.conversion_snapshot_json as char) conversion_snapshot_text,
                       p.sku component_sku,p.name component_name
                from product_bom_item i join product p on p.id=i.component_product_id
                where i.bom_revision_id=? order by i.sort_no,i.id
                """, (rs, rowNum) -> new ProductMasterDataDtos.BomItem(
                rs.getLong("id"), rs.getLong("component_product_id"),
                rs.getString("component_sku"), rs.getString("component_name"),
                rs.getBigDecimal("quantity"), rs.getString("unit"),
                rs.getBigDecimal("base_quantity"), rs.getString("base_unit"),
                zeroToNull(rs.getLong("packaging_effective_revision_id")),
                rs.getString("conversion_snapshot_text"),
                rs.getBigDecimal("scrap_rate"), rs.getString("issue_method"), rs.getInt("sort_no")
        ), revisionId);
        for (ProductMasterDataDtos.BomItem item : items) {
            dataScopeService.assertMasterAllowed("PRODUCT", item.componentProductId());
        }
        return items;
    }

    private RowMapper<ProductMasterDataDtos.Bom> bomMapper() {
        return (rs, rowNum) -> new ProductMasterDataDtos.Bom(
                rs.getLong("id"), rs.getLong("parent_product_id"), rs.getString("bom_code"),
                rs.getString("name"), zeroToNull(rs.getLong("selected_revision_id")),
                rs.getInt("revision_no"), rs.getBigDecimal("output_quantity"),
                rs.getString("output_unit"), rs.getBigDecimal("output_base_quantity"),
                rs.getString("output_base_unit"),
                zeroToNull(rs.getLong("output_effective_revision_id")),
                rs.getString("output_conversion_snapshot_json"),
                rs.getBigDecimal("scrap_rate"),
                date(rs.getDate("effective_from")), date(rs.getDate("effective_to")),
                rs.getString("status"), rs.getString("change_reason"),
                rs.getTimestamp("published_at") == null
                        ? null : rs.getTimestamp("published_at").toLocalDateTime(),
                List.of()
        );
    }

    private ProductMasterDataDtos.Bom withBomItems(ProductMasterDataDtos.Bom row) {
        return new ProductMasterDataDtos.Bom(
                row.id(), row.parentProductId(), row.bomCode(), row.name(),
                row.currentRevisionId(), row.revisionNo(), row.outputQuantity(),
                row.outputUnit(), row.outputBaseQuantity(), row.outputBaseUnit(),
                row.outputEffectiveRevisionId(), row.outputConversionSnapshotJson(),
                row.scrapRate(), row.effectiveFrom(), row.effectiveTo(), row.status(),
                row.changeReason(), row.publishedAt(), bomItems(row.currentRevisionId())
        );
    }

    private RowMapper<ProductMasterDataDtos.BomRevision> bomRevisionMapper() {
        return (rs, rowNum) -> new ProductMasterDataDtos.BomRevision(
                rs.getLong("id"), rs.getLong("bom_id"), rs.getLong("parent_product_id"),
                rs.getString("bom_code_snapshot"), rs.getString("name_snapshot"),
                rs.getString("head_status_snapshot"),
                rs.getBoolean("head_snapshot_available"), rs.getInt("revision_no"),
                rs.getBigDecimal("output_quantity"), rs.getString("output_unit"),
                rs.getBigDecimal("output_base_quantity"), rs.getString("output_base_unit"),
                zeroToNull(rs.getLong("output_effective_revision_id")),
                rs.getString("output_conversion_snapshot_json"),
                rs.getBigDecimal("scrap_rate"), date(rs.getDate("effective_from")),
                date(rs.getDate("effective_to")), rs.getString("content_hash"),
                rs.getString("revision_status"), rs.getString("change_reason"),
                zeroToNull(rs.getLong("published_by")), rs.getString("published_by_name"),
                rs.getTimestamp("published_at").toLocalDateTime(),
                rs.getBoolean("current_revision"), List.of()
        );
    }

    private ProductMasterDataDtos.BomRevision withBomRevisionItems(
            ProductMasterDataDtos.BomRevision row) {
        return new ProductMasterDataDtos.BomRevision(
                row.id(), row.bomId(), row.parentProductId(), row.bomCodeSnapshot(),
                row.nameSnapshot(), row.headStatusSnapshot(), row.headSnapshotAvailable(),
                row.revisionNo(), row.outputQuantity(), row.outputUnit(),
                row.outputBaseQuantity(), row.outputBaseUnit(),
                row.outputEffectiveRevisionId(), row.outputConversionSnapshotJson(),
                row.scrapRate(), row.effectiveFrom(), row.effectiveTo(), row.contentHash(),
                row.revisionStatus(), row.changeReason(), row.publishedBy(),
                row.publishedByName(), row.publishedAt(), row.currentRevision(),
                bomItems(row.id())
        );
    }

    private void refreshStructuredIdentity(Long productId) {
        String identityStatus = jdbc.queryForObject(
                "select identity_status from product where id=?", String.class, productId);
        productMasterDataService.finalizeProductIdentityForProductWrite(
                productId, identityStatus, false);
    }

    private void requirePendingIdentityStructure(Long productId) {
        String identityStatus = jdbc.queryForObject(
                "select identity_status from product where id=? for update",
                String.class, productId);
        if (!"PENDING_REVIEW".equalsIgnoreCase(identityStatus)) {
            throw new BusinessException(
                    "已发布或已停用商品的结构身份不可原地修改；请停用旧商品并新建SKU");
        }
    }

    private String canonicalStructuredText(String value) {
        return StringUtils.hasText(value)
                ? value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT) : "";
    }

    private String canonicalStructuredNumber(BigDecimal value) {
        return value == null ? "" : value.setScale(10).toPlainString();
    }

    private ProductMasterDataDtos.PackagingVariant packagingVariant(Long productId, Long variantId) {
        return packagingVariants(productId).stream().filter(item -> Objects.equals(item.id(), variantId))
                .findFirst().orElseThrow(() -> new BusinessException("包装方案不存在"));
    }

    private ProductMasterDataDtos.SupplierOffer supplierOffer(Long productId, Long offerId) {
        return supplierOffers(productId, null, null, null).stream()
                .filter(item -> Objects.equals(item.id(), offerId)).findFirst()
                .orElseThrow(() -> new BusinessException("供应商供货档案不存在"));
    }

    private void assertPackagingVariantPartnerScope(Long productId, Long variantId) {
        List<Long> supplierIds = jdbc.query("""
                select distinct r.supplier_id
                from packaging_variant_revision r
                where r.packaging_variant_id=? and r.product_id=? and r.supplier_id>0
                """, (rs, rowNum) -> rs.getLong(1), variantId, productId);
        for (Long supplierId : supplierIds) {
            dataScopeService.assertMasterAllowed("PARTNER", supplierId);
        }
    }

    private ProductContext requireProduct(Long productId) {
        dataScopeService.assertMasterAllowed("PRODUCT", productId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id,product_series_id from product where id=?
                """, productId);
        if (rows.isEmpty()) throw new BusinessException("商品不存在");
        Map<String, Object> row = rows.get(0);
        return new ProductContext(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("product_series_id")).longValue()
        );
    }

    private UnitRef requireAllowedUnit(
            ProductContext product, String purpose, String input,
            boolean allowPublishedPackagingRule, String label) {
        String value = required(input, label);
        String key = UnitLookupNormalizer.normalize(value);
        List<Map<String, Object>> matches = jdbc.queryForList("""
                select distinct u.id,u.name,u.abbreviation
                from unit u
                join unit_lookup_token t
                  on t.unit_id=u.id and t.normalized_token=?
                where u.status='ENABLED'
                order by u.id
                """, key);
        if (matches.isEmpty()) {
            throw new BusinessException(label + "“" + value + "”不存在或已停用，请先维护计量单位");
        }
        if (matches.size() > 1) {
            throw new BusinessException(label + "“" + value + "”匹配多个计量单位，请先清理别名冲突");
        }
        Map<String, Object> row = matches.get(0);
        long unitId = ((Number) row.get("id")).longValue();
        String canonical = StringUtils.hasText(String.valueOf(row.get("abbreviation")))
                && !"null".equalsIgnoreCase(String.valueOf(row.get("abbreviation")))
                ? String.valueOf(row.get("abbreviation")).trim()
                : String.valueOf(row.get("name")).trim();
        Integer allowed = jdbc.queryForObject("""
                select count(*) from product_series_allowed_unit
                where product_series_id=? and purpose=? and unit_id=? and status='ENABLED'
                """, Integer.class, product.seriesId(), purpose, unitId);
        if (allowed != null && allowed > 0) return new UnitRef(unitId, canonical);
        if (allowPublishedPackagingRule && packagingRuleAllows(product.id(), purpose, unitId)) {
            return new UnitRef(unitId, canonical);
        }
        throw new BusinessException(label + "“" + canonical + "”未在当前产品系列的"
                + unitPurposeLabel(purpose) + "中启用");
    }

    private boolean packagingRuleAllows(Long productId, String purpose, long unitId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from product_measure_policy m
                join unit u on u.id=?
                left join product_package_rule r
                  on r.product_id=m.product_id
                 and r.effective_revision_id=m.effective_revision_id
                 and r.status='ENABLED'
                where m.product_id=?
                  and (
                    lower(trim(m.base_unit)) in (lower(trim(u.name)),lower(trim(u.abbreviation)))
                    or lower(trim(m.default_purchase_unit))
                         in (lower(trim(u.name)),lower(trim(u.abbreviation)))
                    or (
                      (r.parent_unit_id=u.id or r.child_unit_id=u.id)
                      and (?<>'PURCHASE' or r.purchase_allowed=1 or r.child_unit_id=u.id)
                    )
                  )
                """, Integer.class, unitId, productId, purpose);
        return count != null && count > 0;
    }

    private void requireSupplier(Long supplierId) {
        dataScopeService.assertMasterAllowed("PARTNER", supplierId);
        Integer count = jdbc.queryForObject("""
                select count(*)
                from partner p
                join partner_supplier_profile sp on sp.partner_id=p.id
                where p.id=? and p.status='ENABLED' and sp.status='ENABLED'
                  and (p.type_mask & 2)=2
                """, Integer.class, supplierId);
        if (count == null || count == 0) throw new BusinessException("供应商不存在、已停用或没有供应商身份");
    }

    private BatchContext requireBatch(Long batchId) {
        List<BatchContext> rows = jdbc.query("""
                select id,product_id,warehouse_id,received_at
                from inventory_batch where id=?
                """, (rs, rowNum) -> new BatchContext(
                rs.getLong("id"), rs.getLong("product_id"), rs.getLong("warehouse_id"),
                rs.getDate("received_at").toLocalDate()
        ), batchId);
        if (rows.isEmpty()) throw new BusinessException("库存批次不存在");
        BatchContext batch = rows.get(0);
        dataScopeService.assertMasterAllowed("PRODUCT", batch.productId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", batch.warehouseId());
        return batch;
    }

    private void assertQualityCertificatePartnerScope(
            Long batchId, Long certificateId) {
        List<Long> supplierIds = jdbc.query("""
                select supplier_id
                from inventory_batch_quality_certificate
                where id=? and inventory_batch_id=?
                """, (rs, rowNum) -> zeroToNull(rs.getLong(1)),
                certificateId, batchId);
        if (supplierIds.isEmpty()) {
            throw new BusinessException("批次质保书不存在");
        }
        Long supplierId = supplierIds.get(0);
        if (supplierId != null) {
            dataScopeService.assertMasterAllowed("PARTNER", supplierId);
        }
    }

    private List<QualityResultPrepared> validateQualityResults(
            BatchContext batch, List<ProductMasterDataDtos.QualityResultSave> requests) {
        List<ProductMasterDataDtos.QualityResultSave> input =
                requests == null ? List.of() : requests;
        List<Long> revisionIds = jdbc.query("""
                select r.id from product_technical_spec_revision r
                where r.product_id=? and r.status='PUBLISHED' and r.effective_from<=?
                  and (r.effective_to is null or r.effective_to>=?)
                  and not exists (
                    select 1 from product_technical_spec_revision r2
                    where r2.product_id=r.product_id and r2.status='PUBLISHED'
                      and r2.effective_from<=?
                      and (r2.effective_from>r.effective_from
                           or (r2.effective_from=r.effective_from
                               and r2.revision_no>r.revision_no))
                  )
                """, (rs, rowNum) -> rs.getLong(1), batch.productId(),
                sqlDate(batch.receivedAt()), sqlDate(batch.receivedAt()),
                sqlDate(batch.receivedAt()));
        if (revisionIds.isEmpty()) {
            if (!input.isEmpty()) {
                throw new BusinessException("该批次收货日期没有适用的已发布技术规格，不能登记实测结论");
            }
            return List.of();
        }
        long revisionId = revisionIds.get(0);
        Map<Long, Map<String, Object>> characteristics = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select id,characteristic_code,value_type,target_text,minimum_value,target_value,
                       maximum_value,unit_name,test_method,is_batch_required
                from product_technical_characteristic
                where technical_spec_revision_id=?
                order by sort_no,id
                """, revisionId)) {
            characteristics.put(((Number) row.get("id")).longValue(), row);
        }
        Set<Long> supplied = new HashSet<>();
        Set<String> codes = new HashSet<>();
        List<QualityResultPrepared> result = new ArrayList<>();
        for (ProductMasterDataDtos.QualityResultSave item : input) {
            if (item == null || item.technicalCharacteristicId() == null) {
                throw new BusinessException("质量实测项必须选择当前批次适用的技术特性");
            }
            Map<String, Object> characteristic = characteristics.get(item.technicalCharacteristicId());
            if (characteristic == null) {
                throw new BusinessException("质量实测项不属于该商品在批次收货日期适用的技术规格");
            }
            if (!supplied.add(item.technicalCharacteristicId())) {
                throw new BusinessException("同一技术特性不能重复登记实测结果");
            }
            String code = String.valueOf(characteristic.get("characteristic_code"));
            if (!code.equalsIgnoreCase(required(item.characteristicCode(), "技术特性编码"))) {
                throw new BusinessException("质量实测项编码与所选技术特性不一致");
            }
            if (!codes.add(code.toUpperCase(Locale.ROOT))) {
                throw new BusinessException("质量实测特性编码不能重复");
            }
            String unit = text(characteristic.get("unit_name"));
            if (StringUtils.hasText(unit)
                    && !unit.equalsIgnoreCase(required(item.unitName(), "实测单位"))) {
                throw new BusinessException("技术特性“" + code + "”必须使用单位“" + unit + "”");
            }
            String conclusion = deriveQualityConclusion(characteristic, item);
            if (StringUtils.hasText(item.conclusion())
                    && !conclusion.equalsIgnoreCase(item.conclusion())
                    && !"NOT_EVALUATED".equalsIgnoreCase(item.conclusion())) {
                throw new BusinessException("技术特性“" + code + "”的提交结论与技术范围计算结果不一致");
            }
            result.add(new QualityResultPrepared(
                    item.technicalCharacteristicId(), code.toUpperCase(Locale.ROOT),
                    text(item.measuredText()), item.measuredValue(), unit, conclusion,
                    text(characteristic.get("test_method")), item.testedAt()));
        }
        for (Map.Entry<Long, Map<String, Object>> entry : characteristics.entrySet()) {
            if (((Number) entry.getValue().get("is_batch_required")).intValue() == 1
                    && !supplied.contains(entry.getKey())) {
                throw new BusinessException("缺少批次必检技术特性“"
                        + entry.getValue().get("characteristic_code") + "”的实测结果");
            }
        }
        return result;
    }

    private String deriveQualityConclusion(
            Map<String, Object> characteristic,
            ProductMasterDataDtos.QualityResultSave result) {
        String valueType = String.valueOf(characteristic.get("value_type"));
        if (Set.of("NUMBER", "RANGE").contains(valueType)) {
            if (result.measuredValue() == null) throw new BusinessException("数值型技术特性必须填写实测数值");
            BigDecimal minimum = decimal(characteristic.get("minimum_value"));
            BigDecimal maximum = decimal(characteristic.get("maximum_value"));
            BigDecimal target = decimal(characteristic.get("target_value"));
            boolean hasRule = minimum != null || maximum != null || target != null;
            boolean pass = (minimum == null || result.measuredValue().compareTo(minimum) >= 0)
                    && (maximum == null || result.measuredValue().compareTo(maximum) <= 0)
                    && ((minimum != null || maximum != null || target == null)
                        || result.measuredValue().compareTo(target) == 0);
            return !hasRule ? "NOT_EVALUATED" : pass ? "PASS" : "FAIL";
        }
        String target = text(characteristic.get("target_text"));
        if (!StringUtils.hasText(target)) return "NOT_EVALUATED";
        if ("BOOLEAN".equals(valueType)
                && !Set.of("true", "false").contains(
                        canonicalStructuredText(result.measuredText()))) {
            throw new BusinessException("布尔型技术特性实测值只能填写true或false");
        }
        return canonicalStructuredText(target).equals(canonicalStructuredText(result.measuredText()))
                ? "PASS" : "FAIL";
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal number ? number : new BigDecimal(String.valueOf(value));
    }

    private SourceRevision requireSourceRevision(
            Long sourceDocumentId, Long sourceDocumentRevisionId) {
        if ((sourceDocumentId == null || sourceDocumentId <= 0)
                && (sourceDocumentRevisionId == null || sourceDocumentRevisionId <= 0)) {
            return new SourceRevision(0L, 0L);
        }
        if (sourceDocumentRevisionId == null || sourceDocumentRevisionId <= 0) {
            throw new BusinessException("引用来源资料时必须明确选择不可变的资料修订版本");
        }
        List<SourceRevision> rows = jdbc.query("""
                select d.id,r.id
                from product_source_document_revision r
                join product_source_document d on d.id=r.document_id
                where r.id=? and d.status='ENABLED'
                """, (rs, rowNum) -> new SourceRevision(rs.getLong(1), rs.getLong(2)),
                sourceDocumentRevisionId);
        if (rows.isEmpty()) throw new BusinessException("来源资料修订不存在或资料已停用");
        SourceRevision source = rows.get(0);
        if (sourceDocumentId != null && sourceDocumentId > 0
                && !Objects.equals(sourceDocumentId, source.documentId())) {
            throw new BusinessException("来源资料修订不属于所选资料");
        }
        return source;
    }

    private int nextVersion(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 1 : value;
    }

    private String conversionMode(String value) {
        String mode = required(value, "包装换算方式").toUpperCase(Locale.ROOT);
        if (!Set.of("FIXED", "ACTUAL_WEIGHT", "THEORETICAL").contains(mode)) {
            throw new BusinessException("不支持的包装换算方式");
        }
        return mode;
    }

    private String characteristicType(String value) {
        String type = required(value, "技术特性类型").toUpperCase(Locale.ROOT);
        if (!Set.of("TEXT", "NUMBER", "RANGE", "BOOLEAN").contains(type)) {
            throw new BusinessException("不支持的技术特性类型");
        }
        return type;
    }

    private String certificateType(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "COA";
    }

    private String conclusion(String value) {
        String result = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT) : "NOT_EVALUATED";
        if (!Set.of("PASS", "FAIL", "NOT_EVALUATED").contains(result)) {
            throw new BusinessException("质检结论不正确");
        }
        return result;
    }

    private String issueMethod(String value) {
        String method = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT) : "MANUAL";
        if (!Set.of("MANUAL", "BACKFLUSH").contains(method)) {
            throw new BusinessException("BOM发料方式不正确");
        }
        return method;
    }

    private String currency(String value) {
        String currency = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT) : "CNY";
        if (!currency.matches("[A-Z]{3}")) throw new BusinessException("币种必须使用三位字母代码");
        return currency;
    }

    private BigDecimal positive(BigDecimal value) {
        return value != null && value.compareTo(ZERO) > 0 ? value : null;
    }

    private BigDecimal positivePrice(BigDecimal value) {
        if (value == null) return null;
        if (value.compareTo(ZERO) <= 0) throw new BusinessException("供应商报价单价必须大于0");
        return value;
    }

    private BigDecimal rate(BigDecimal value) {
        if (value == null) return ZERO;
        if (value.compareTo(ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("比例必须在0到100之间");
        }
        return value;
    }

    private void validateDates(LocalDate from, LocalDate to, String label) {
        if (from == null) throw new BusinessException(label + "必须填写生效日期");
        if (to != null && to.isBefore(from)) throw new BusinessException(label + "失效日期不能早于生效日期");
    }

    private String status(String value) {
        String normalized = required(value, "状态").toUpperCase(Locale.ROOT);
        if (!Set.of("ENABLED", "DISABLED").contains(normalized)) {
            throw new BusinessException("状态只支持ENABLED或DISABLED");
        }
        return normalized;
    }

    private void requireExpectedRevision(Integer expected, int current, String label) {
        if (expected == null) {
            throw new BusinessException(label + "缺少预期版本，请刷新后重新提交");
        }
        if (expected != current) {
            throw new BusinessException(label + "已由其他人更新，当前版本为" + current + "，请刷新后重新编辑");
        }
    }

    private String unitPurposeLabel(String purpose) {
        return switch (purpose) {
            case "PACKAGING" -> "包装单位";
            case "PURCHASE" -> "采购单位";
            case "PRICE" -> "计价单位";
            default -> "可用单位";
        };
    }

    private String normalizedUnit(String value) {
        return StringUtils.hasText(value)
                ? value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT) : "";
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException("请填写" + label);
        return value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long id(Long value) {
        return value == null || value <= 0 ? 0 : value;
    }

    private Long zeroToNull(long value) {
        return value > 0 ? value : null;
    }

    private Date sqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private int bool(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String validJson(String value, String fallback) {
        String candidate = StringUtils.hasText(value) ? value.trim() : fallback;
        try {
            objectMapper.readTree(candidate);
            return candidate;
        } catch (JsonProcessingException ex) {
            throw new BusinessException("商业条款JSON格式不正确");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("主数据快照无法生成");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成主数据内容哈希", ex);
        }
    }

    private SecurityContext.Principal requireViewPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(VIEW_PERMISSION) && !actor.hasPermission(MANAGE_PERMISSION)) {
            throw new ForbiddenException("当前账号无权查看复杂商品主数据");
        }
        return actor;
    }

    private SecurityContext.Principal requireManagePermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(MANAGE_PERMISSION)) {
            throw new ForbiddenException("当前账号无权维护或发布复杂商品主数据");
        }
        return actor;
    }

    private SecurityContext.Principal requireQualityViewPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("inventory.quality.view")
                && !actor.hasPermission("inventory.quality.manage")) {
            throw new ForbiddenException("当前账号无权查看批次质量和质保书");
        }
        return actor;
    }

    private SecurityContext.Principal requireSupplierOfferViewPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("product.cost.view")
                && !actor.hasPermission("product.supplier.offer.manage")) {
            throw new ForbiddenException("当前账号无权查看供应商报价和供货条款");
        }
        return actor;
    }

    private SecurityContext.Principal requireSupplierOfferManagePermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("product.supplier.offer.manage")) {
            throw new ForbiddenException("当前账号无权维护供应商供货档案");
        }
        return actor;
    }

    private SecurityContext.Principal requireQualityManagePermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("inventory.quality.manage")) {
            throw new ForbiddenException("当前账号无权维护批次质量和质保书");
        }
        return actor;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new BusinessException("新增复杂商品主数据失败");
        return key.longValue();
    }

    private record ProductContext(Long id, Long seriesId) {
    }

    private record UnitRef(Long id, String canonicalName) {
    }

    private record OfferUnits(String purchaseUnit, String priceUnit) {
    }

    private record BatchContext(
            Long id, Long productId, Long warehouseId, LocalDate receivedAt) {
    }

    private record QualityResultPrepared(
            Long technicalCharacteristicId, String characteristicCode,
            String measuredText, BigDecimal measuredValue, String unitName,
            String conclusion, String testMethodSnapshot, LocalDateTime testedAt) {
    }

    private record SourceRevision(Long documentId, Long revisionId) {
    }

    private record BomTemporalRevision(
            Long id, Long parentProductId, Integer revisionNo,
            LocalDate effectiveFrom, LocalDate effectiveTo,
            Set<Long> componentIds, boolean candidate) {
    }
}
