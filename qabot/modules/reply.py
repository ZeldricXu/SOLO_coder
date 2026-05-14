from typing import Optional, Dict, Any, List
from qabot.models import Database, Knowledge, ReplyTemplate, ReplyTemplateCreate
from qabot.config import settings


class ReplyTypeProcessor:
    def __init__(self, db: Database):
        self.db = db
    
    def process_knowledge_match(
        self,
        knowledge: Knowledge,
        config: Dict[str, Any]
    ) -> str:
        template_id = config.get("template_id")
        if template_id:
            template = self.db.get_reply_template(template_id)
            if template and "{content}" in template.template_content:
                return template.template_content.format(content=knowledge.knowledge_content)
        return knowledge.knowledge_content
    
    def process_template(
        self,
        knowledge: Knowledge,
        config: Dict[str, Any]
    ) -> str:
        template_id = config.get("template_id")
        if template_id:
            template = self.db.get_reply_template(template_id)
            if template:
                if "{content}" in template.template_content:
                    return template.template_content.format(content=knowledge.knowledge_content)
                return template.template_content
        
        general_template = self.db.get_reply_template("template_general")
        if general_template and "{content}" in general_template.template_content:
            return general_template.template_content.format(content=knowledge.knowledge_content)
        return knowledge.knowledge_content
    
    def process_hybrid(
        self,
        knowledge: Knowledge,
        config: Dict[str, Any]
    ) -> str:
        template_id = config.get("template_id", "template_hybrid")
        template = self.db.get_reply_template(template_id)
        
        if template and "{content}" in template.template_content:
            return template.template_content.format(content=knowledge.knowledge_content)
        
        prefix = "根据您的问题，为您找到以下解答：\n\n"
        suffix = "\n\n如需更多帮助，请联系客服。"
        return prefix + knowledge.knowledge_content + suffix
    
    def process_default(
        self,
        config: Dict[str, Any]
    ) -> str:
        template_id = config.get("template_id", settings.DEFAULT_TEMPLATE_ID)
        template = self.db.get_reply_template(template_id)
        if template:
            return template.template_content
        return "抱歉，未能找到匹配答案。您可以尝试换一种方式提问，或联系客服获取帮助。"
    
    def process(
        self,
        reply_type: str,
        knowledge: Optional[Knowledge],
        config: Dict[str, Any]
    ) -> Optional[str]:
        requires_match = config.get("requires_match", True)
        
        if requires_match and knowledge is None:
            return None
        
        if reply_type == "knowledge_match":
            return self.process_knowledge_match(knowledge, config)
        elif reply_type == "template":
            return self.process_template(knowledge, config)
        elif reply_type == "hybrid":
            return self.process_hybrid(knowledge, config)
        elif reply_type == "default":
            return self.process_default(config)
        
        return None


class ReplyModule:
    def __init__(self, db: Database):
        self.db = db
        self.processor = ReplyTypeProcessor(db)
    
    def _get_enabled_reply_types(self, has_match: bool) -> List[tuple]:
        reply_types = []
        all_types = settings.reply_type.REPLY_TYPES
        
        sorted_types = sorted(
            all_types.items(),
            key=lambda x: x[1].get("priority", 999)
        )
        
        for reply_type, config in sorted_types:
            if not config.get("enabled", False):
                continue
            
            requires_match = config.get("requires_match", True)
            if has_match or not requires_match:
                reply_types.append((reply_type, config))
        
        return reply_types
    
    def get_default_reply(self) -> str:
        default_config = settings.get_reply_type_config("default")
        return self.processor.process_default(default_config)
    
    def generate_knowledge_reply(self, knowledge: Knowledge, reply_type: str = None) -> str:
        if reply_type:
            config = settings.get_reply_type_config(reply_type)
            if config and config.get("enabled", False):
                result = self.processor.process(reply_type, knowledge, config)
                if result:
                    return result
        
        knowledge_config = settings.get_reply_type_config("knowledge_match")
        return self.processor.process_knowledge_match(knowledge, knowledge_config)
    
    def generate_reply(
        self,
        matched_knowledge: Optional[Knowledge] = None,
        preferred_reply_type: Optional[str] = None
    ) -> tuple[str, str]:
        has_match = matched_knowledge is not None
        
        if preferred_reply_type and has_match:
            config = settings.get_reply_type_config(preferred_reply_type)
            if config and config.get("enabled", False):
                result = self.processor.process(preferred_reply_type, matched_knowledge, config)
                if result:
                    return result, preferred_reply_type
        
        if has_match:
            enabled_types = self._get_enabled_reply_types(has_match=True)
            
            for reply_type, config in enabled_types:
                if reply_type == "default":
                    continue
                
                result = self.processor.process(reply_type, matched_knowledge, config)
                if result:
                    return result, reply_type
        
        default_config = settings.get_reply_type_config("default")
        result = self.processor.process_default(default_config)
        return result, "default"
    
    def list_templates(self) -> List[ReplyTemplate]:
        return self.db.list_reply_templates()
    
    def create_template(self, data: ReplyTemplateCreate) -> ReplyTemplate:
        return self.db.create_reply_template(data)
    
    def list_reply_types(self) -> Dict[str, Dict[str, Any]]:
        return dict(settings.reply_type.REPLY_TYPES)
    
    def get_reply_type_config(self, reply_type: str) -> Optional[Dict[str, Any]]:
        return settings.get_reply_type_config(reply_type)
    
    def add_reply_type(
        self,
        reply_type: str,
        config: Dict[str, Any]
    ) -> bool:
        if reply_type in settings.reply_type.REPLY_TYPES:
            return False
        
        default_config = {
            "name": config.get("name", reply_type),
            "description": config.get("description", ""),
            "enabled": config.get("enabled", True),
            "priority": config.get("priority", 99),
            "requires_match": config.get("requires_match", True),
            "template_id": config.get("template_id")
        }
        
        settings.reply_type.REPLY_TYPES[reply_type] = default_config
        return True
    
    def update_reply_type(
        self,
        reply_type: str,
        config_updates: Dict[str, Any]
    ) -> bool:
        if reply_type not in settings.reply_type.REPLY_TYPES:
            return False
        
        current = settings.reply_type.REPLY_TYPES[reply_type]
        current.update(config_updates)
        return True
    
    def enable_reply_type(self, reply_type: str) -> bool:
        return self.update_reply_type(reply_type, {"enabled": True})
    
    def disable_reply_type(self, reply_type: str) -> bool:
        if reply_type == "default":
            return False
        return self.update_reply_type(reply_type, {"enabled": False})
    
    def set_reply_type_priority(self, reply_type: str, priority: int) -> bool:
        return self.update_reply_type(reply_type, {"priority": priority})
