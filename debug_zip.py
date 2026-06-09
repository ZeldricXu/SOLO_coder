import zipfile
import os
import io
import tempfile

temp_dir = tempfile.mkdtemp()

from reportlab.lib.pagesizes import A4
from reportlab.platypus import SimpleDocTemplate, Paragraph
from reportlab.lib.styles import getSampleStyleSheet
from PIL import Image, ImageDraw, ImageFont

pdf_path = os.path.join(temp_dir, 'sample.pdf')
doc = SimpleDocTemplate(pdf_path, pagesize=A4)
story = [Paragraph('Sample', getSampleStyleSheet()['Normal'])]
doc.build(story)

img = Image.new('RGB', (1200, 1600), color='white')
draw = ImageDraw.Draw(img)
font = ImageFont.load_default()
draw.text((100, 80), 'Test', fill='black', font=font)
img_path = os.path.join(temp_dir, 'invoice_1.jpg')
img.save(img_path, 'JPEG', quality=85)

zip_path = os.path.join(temp_dir, 'test.zip')
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    zf.write(pdf_path, arcname='report_1.pdf')
    zf.write(img_path, arcname='invoice_1.jpg')
    for i in range(3):
        doc_path = os.path.join(temp_dir, f'doc_{i}.pdf')
        doc = SimpleDocTemplate(doc_path, pagesize=A4)
        story = [Paragraph(f'Document {i}', getSampleStyleSheet()['Normal'])]
        doc.build(story)
        zf.write(doc_path, arcname=f'doc_{i}.pdf')

from app.ml.parsers import ParserFactory
from app.schemas.document import DocumentTypeEnum

with open(zip_path, 'rb') as f:
    zip_data = f.read()

documents = []
position = 0

with zipfile.ZipFile(io.BytesIO(zip_data)) as zf:
    print(f"All files in zip: {zf.namelist()}")
    for info in zf.infolist():
        if info.is_dir():
            continue
        filename = os.path.basename(info.filename)
        print(f'Processing: {filename}, size: {info.file_size}')
        if not filename or filename.startswith('.'):
            print('  Skipping: hidden or empty')
            continue
        try:
            doc_type = ParserFactory.detect_document_type(filename)
            print(f'  Type: {doc_type}')
            if doc_type not in [DocumentTypeEnum.PDF, DocumentTypeEnum.WORD, DocumentTypeEnum.IMAGE, DocumentTypeEnum.TXT]:
                print('  Skipping: unsupported type')
                continue
            with zf.open(info) as f:
                content = f.read()
            if len(content) == 0:
                print('  Skipping: empty content')
                continue
            print(f'  Accepted: {filename}')
            documents.append({'filename': filename, 'position': position})
            position += 1
        except Exception as e:
            print(f'  Error: {e}')
            continue

print(f'Total accepted: {len(documents)}')
for d in documents:
    print(f'  - {d}')
