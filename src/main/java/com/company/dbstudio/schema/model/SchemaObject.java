package com.company.dbstudio.schema.model;

import java.util.ArrayList;
import java.util.List;

public class SchemaObject {

    public enum ObjectType {
        SCHEMA("Schema", "📁"),
        TABLE("Table", "📄"),
        VIEW("View", "👁️"),
        COLUMN("Column", "📋"),
        INDEX("Index", "🔍"),
        PRIMARY_KEY("Primary Key", "🔑"),
        FOREIGN_KEY("Foreign Key", "🔗"),
        UNIQUE_KEY("Unique Key", "🔒"),
        TRIGGER("Trigger", "⚡"),
        PROCEDURE("Procedure", "📦"),
        FUNCTION("Function", "⚙️"),
        SEQUENCE("Sequence", "🔢"),
        PACKAGE("Package", "📦"),
        SYNONYM("Synonym", "🔄");

        private final String displayName;
        private final String icon;

        ObjectType(String displayName, String icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getIcon() {
            return icon;
        }
    }

    private final ObjectType type;
    private final String name;
    private final String schemaName;
    private final String parentName;
    private String comment;
    private String ddl;
    private final List<SchemaObject> children;
    private boolean loaded;

    public SchemaObject(ObjectType type, String name) {
        this(type, name, null, null);
    }

    public SchemaObject(ObjectType type, String name, String schemaName) {
        this(type, name, schemaName, null);
    }

    public SchemaObject(ObjectType type, String name, String schemaName, String parentName) {
        this.type = type;
        this.name = name;
        this.schemaName = schemaName;
        this.parentName = parentName;
        this.children = new ArrayList<>();
        this.loaded = false;
    }

    public ObjectType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getParentName() {
        return parentName;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getDdl() {
        return ddl;
    }

    public void setDdl(String ddl) {
        this.ddl = ddl;
    }

    public List<SchemaObject> getChildren() {
        return children;
    }

    public void addChild(SchemaObject child) {
        children.add(child);
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public String getFullName() {
        if (schemaName != null && !schemaName.isEmpty()) {
            return schemaName + "." + name;
        }
        return name;
    }

    public String getDisplayName() {
        return type.getIcon() + " " + name;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
