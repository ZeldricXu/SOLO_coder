import csv
import json
import os
from typing import Dict, List, Any, Union
from pathlib import Path
import numpy as np

class DataParserError(Exception):
    pass

class UnsupportedFormatError(DataParserError):
    pass

class ParseError(DataParserError):
    pass

class DataParser:
    
    SUPPORTED_FORMATS = ['csv', 'json']
    
    def __init__(self):
        self.supported_formats = self.SUPPORTED_FORMATS
    
    def parse_file(self, file_path: Union[str, Path]) -> Dict[str, Any]:
        file_path = Path(file_path)
        
        if not file_path.exists():
            raise ParseError(f"File not found: {file_path}")
        
        file_format = self._detect_format(file_path)
        
        if file_format == 'csv':
            return self._parse_csv(file_path)
        elif file_format == 'json':
            return self._parse_json(file_path)
        else:
            raise UnsupportedFormatError(f"Unsupported format: {file_format}")
    
    def _detect_format(self, file_path: Path) -> str:
        suffix = file_path.suffix.lower().lstrip('.')
        
        if suffix in self.supported_formats:
            return suffix
        
        with open(file_path, 'r', encoding='utf-8') as f:
            first_char = f.read(1).strip()
            if first_char in ['{', '[']:
                return 'json'
        
        raise UnsupportedFormatError(f"Cannot detect format for file: {file_path}")
    
    def _parse_csv(self, file_path: Path) -> Dict[str, Any]:
        try:
            data = {
                'format': 'csv',
                'metadata': {},
                'data': [],
                'columns': []
            }
            
            with open(file_path, 'r', encoding='utf-8', newline='') as f:
                reader = csv.DictReader(f)
                data['columns'] = reader.fieldnames if reader.fieldnames else []
                
                for row in reader:
                    parsed_row = self._parse_row_values(row)
                    data['data'].append(parsed_row)
            
            data['metadata']['row_count'] = len(data['data'])
            data['metadata']['column_count'] = len(data['columns'])
            
            return self._convert_to_internal_structure(data)
            
        except Exception as e:
            raise ParseError(f"CSV parse error: {str(e)}")
    
    def _parse_json(self, file_path: Path) -> Dict[str, Any]:
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                raw_data = json.load(f)
            
            data = {
                'format': 'json',
                'metadata': {},
                'data': []
            }
            
            if isinstance(raw_data, list):
                data['data'] = raw_data
            elif isinstance(raw_data, dict):
                if 'data' in raw_data and isinstance(raw_data['data'], list):
                    data['data'] = raw_data['data']
                    data['metadata'] = {k: v for k, v in raw_data.items() if k != 'data'}
                else:
                    data['data'] = [raw_data]
            else:
                raise ParseError(f"Unexpected JSON structure: {type(raw_data)}")
            
            data['metadata']['record_count'] = len(data['data'])
            
            return self._convert_to_internal_structure(data)
            
        except json.JSONDecodeError as e:
            raise ParseError(f"JSON decode error: {str(e)}")
        except Exception as e:
            raise ParseError(f"JSON parse error: {str(e)}")
    
    def _parse_row_values(self, row: Dict[str, str]) -> Dict[str, Any]:
        parsed = {}
        for key, value in row.items():
            if value is None or value == '':
                parsed[key] = None
            else:
                try:
                    parsed[key] = int(value)
                    continue
                except ValueError:
                    pass
                try:
                    parsed[key] = float(value)
                    continue
                except ValueError:
                    pass
                parsed[key] = value
        return parsed
    
    def _convert_to_internal_structure(self, data: Dict[str, Any]) -> Dict[str, Any]:
        internal = {
            'format': data['format'],
            'metadata': data.get('metadata', {}),
            'records': data.get('data', []),
            'numeric_arrays': {}
        }
        
        if internal['records']:
            first_record = internal['records'][0]
            if isinstance(first_record, dict):
                columns = list(first_record.keys())
                for col in columns:
                    values = []
                    for record in internal['records']:
                        val = record.get(col)
                        if isinstance(val, (int, float)):
                            values.append(val)
                    if values:
                        internal['numeric_arrays'][col] = np.array(values)
        
        return internal
    
    def to_numpy_array(self, data: Dict[str, Any], field_name: str = None) -> np.ndarray:
        if field_name and field_name in data.get('numeric_arrays', {}):
            return data['numeric_arrays'][field_name]
        
        records = data.get('records', [])
        if not records:
            return np.array([])
        
        if isinstance(records[0], (list, tuple)):
            return np.array(records)
        elif isinstance(records[0], dict):
            numeric_values = []
            for record in records:
                row = []
                for val in record.values():
                    if isinstance(val, (int, float)):
                        row.append(val)
                if row:
                    numeric_values.append(row)
            return np.array(numeric_values)
        
        return np.array([])
