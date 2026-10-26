# Local PaddleOCR service

This optional private service recognizes visible text in uploaded package-label
images. It does not enable QR or barcode decoding.

Install the pinned CPU runtime:

```bash
scripts/ocr/install-local-paddleocr.sh
```

Start the service on loopback only:

```bash
scripts/ocr/start-local-paddleocr.sh
```

Models and temporary inference files are kept under
`data/local/ocr/cache` rather than a user home directory. The default model
source is Paddle BOS and can be overridden with `PADDLE_PDX_MODEL_SOURCE`.

Start Copper Backend with:

```bash
COPPER_OCR_ENABLED=true
COPPER_OCR_ENDPOINT=http://127.0.0.1:8080/ocr
```

The OCR result is always a candidate for human review. It does not create or
post inbound, outbound, inventory, settlement, or accounting records.
