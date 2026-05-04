import os
import logging

logger = logging.getLogger(__name__)


class TextExtractor:
    @staticmethod
    def extract_text(file_path):
        if not os.path.exists(file_path):
            logger.error(f"File not found: {file_path}")
            return None
        
        ext = os.path.splitext(file_path)[1].lower()
        
        try:
            if ext == ".txt":
                return TextExtractor._extract_from_txt(file_path)
            elif ext == ".pdf":
                return TextExtractor._extract_from_pdf(file_path)
            else:
                logger.warning(f"Unsupported file format: {ext}")
                return None
        except Exception as e:
            logger.error(f"Error extracting text from {file_path}: {str(e)}")
            return None
    
    @staticmethod
    def _extract_from_txt(file_path):
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
            return content
        except UnicodeDecodeError:
            try:
                with open(file_path, "r", encoding="gbk") as f:
                    content = f.read()
                return content
            except Exception as e:
                logger.error(f"Failed to read text file with different encodings: {str(e)}")
                return None
    
    @staticmethod
    def _extract_from_pdf(file_path):
        try:
            import PyPDF2
            with open(file_path, "rb") as f:
                reader = PyPDF2.PdfReader(f)
                text = ""
                for page in reader.pages:
                    text += page.extract_text() + "\n"
            return text.strip()
        except ImportError:
            logger.error("PyPDF2 not installed. Please install it with: pip install PyPDF2")
            return None
        except Exception as e:
            logger.error(f"Error extracting text from PDF: {str(e)}")
            return None
