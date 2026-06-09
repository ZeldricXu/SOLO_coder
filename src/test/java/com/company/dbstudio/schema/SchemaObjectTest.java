package com.company.dbstudio.schema;

import com.company.dbstudio.schema.model.SchemaObject;
import com.company.dbstudio.schema.model.SchemaObject.ObjectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Schema对象模型测试")
class SchemaObjectTest {

    @Test
    @DisplayName("创建表对象")
    void createTableObject_ShouldHaveCorrectType() {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");

        assertThat(table.getType()).isEqualTo(ObjectType.TABLE);
        assertThat(table.getName()).isEqualTo("users");
        assertThat(table.getFullName()).isEqualTo("users");
        assertThat(table.getIcon()).isNotNull();
        assertThat(table.getDisplayTypeName()).isEqualTo("表");
    }

    @Test
    @DisplayName("创建带Schema的表对象")
    void createTableObject_WithSchema_ShouldHaveCorrectFullName() {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "public.users", "users");
        table.setSchemaName("public");

        assertThat(table.getSchemaName()).isEqualTo("public");
        assertThat(table.getFullName()).isEqualTo("public.users");
    }

    @Test
    @DisplayName("对象类型显示名称")
    void getDisplayTypeName_ShouldReturnChineseNames() {
        assertThat(ObjectType.TABLE.getDisplayName()).isEqualTo("表");
        assertThat(ObjectType.VIEW.getDisplayName()).isEqualTo("视图");
        assertThat(ObjectType.COLUMN.getDisplayName()).isEqualTo("列");
        assertThat(ObjectType.INDEX.getDisplayName()).isEqualTo("索引");
        assertThat(ObjectType.PRIMARY_KEY.getDisplayName()).isEqualTo("主键");
        assertThat(ObjectType.FOREIGN_KEY.getDisplayName()).isEqualTo("外键");
        assertThat(ObjectType.TRIGGER.getDisplayName()).isEqualTo("触发器");
        assertThat(ObjectType.PROCEDURE.getDisplayName()).isEqualTo("存储过程");
        assertThat(ObjectType.FUNCTION.getDisplayName()).isEqualTo("函数");
    }

    @ParameterizedTest
    @CsvSource({
            "TABLE,       true",
            "VIEW,        true",
            "COLUMN,      false",
            "INDEX,       false",
            "SCHEMA,      false"
    })
    @DisplayName("判断是否为表或视图")
    void isTableOrView_ShouldReturnCorrectValue(ObjectType type, boolean expected) {
        SchemaObject obj = new SchemaObject(type, "test", "test");
        assertThat(obj.isTableOrView()).isEqualTo(expected);
    }

    @Test
    @DisplayName("父子关系")
    void parentChildRelationship_ShouldWork() {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");
        SchemaObject column1 = new SchemaObject(ObjectType.COLUMN, "id", "id");
        SchemaObject column2 = new SchemaObject(ObjectType.COLUMN, "name", "name");

        table.addChild(column1);
        table.addChild(column2);

        assertThat(table.getChildren()).hasSize(2);
        assertThat(table.getChildren()).extracting(SchemaObject::getName)
                .containsExactly("id", "name");
        assertThat(column1.getParent()).isSameAs(table);
        assertThat(table.hasChildren()).isTrue();
    }

    @Test
    @DisplayName("加载状态")
    void loadedState_ShouldBeTracked() {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");
        assertThat(table.isLoaded()).isFalse();

        table.setLoaded(true);
        assertThat(table.isLoaded()).isTrue();
    }

    @Test
    @DisplayName("DDL存储")
    void ddlStorage_ShouldWork() {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");
        String ddl = "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100));";

        table.setDdl(ddl);
        assertThat(table.getDdl()).isEqualTo(ddl);
    }

    @Test
    @DisplayName("注释存储")
    void commentStorage_ShouldWork() {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");
        table.setComment("用户信息表");

        assertThat(table.getComment()).isEqualTo("用户信息表");
    }

    @Test
    @DisplayName("按类型筛选子节点")
    void getChildrenByType_ShouldFilterCorrectly() {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");
        table.addChild(new SchemaObject(ObjectType.COLUMN, "id", "id"));
        table.addChild(new SchemaObject(ObjectType.COLUMN, "name", "name"));
        table.addChild(new SchemaObject(ObjectType.INDEX, "idx_name", "idx_name"));
        table.addChild(new SchemaObject(ObjectType.PRIMARY_KEY, "pk_id", "pk_id"));

        List<SchemaObject> columns = table.getChildrenByType(ObjectType.COLUMN);
        assertThat(columns).hasSize(2);
        assertThat(columns).extracting(SchemaObject::getName).containsExactly("id", "name");

        List<SchemaObject> indexes = table.getChildrenByType(ObjectType.INDEX);
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).getName()).isEqualTo("idx_name");
    }

    @Test
    @DisplayName("对象类型图标")
    void getIcon_ShouldReturnIconForAllTypes() {
        for (ObjectType type : ObjectType.values()) {
            SchemaObject obj = new SchemaObject(type, "test", "test");
            assertThat(obj.getIcon()).isNotNull();
            assertThat(obj.getIcon()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("可展开对象类型")
    void isExpandable_ShouldReturnTrueForContainerTypes() {
        assertThat(ObjectType.SCHEMA.isExpandable()).isTrue();
        assertThat(ObjectType.TABLE.isExpandable()).isTrue();
        assertThat(ObjectType.VIEW.isExpandable()).isTrue();
        assertThat(ObjectType.COLUMN.isExpandable()).isFalse();
        assertThat(ObjectType.INDEX.isExpandable()).isFalse();
    }

    @Test
    @DisplayName("equals和hashCode基于名称和类型")
    void equalsAndHashCode_ShouldBeBasedOnNameAndType() {
        SchemaObject obj1 = new SchemaObject(ObjectType.TABLE, "users", "users");
        SchemaObject obj2 = new SchemaObject(ObjectType.TABLE, "users", "users");
        SchemaObject obj3 = new SchemaObject(ObjectType.VIEW, "users", "users");

        assertThat(obj1).isEqualTo(obj2);
        assertThat(obj1).isNotEqualTo(obj3);
        assertThat(obj1.hashCode()).isEqualTo(obj2.hashCode());
    }

    @Test
    @DisplayName("toString返回全名")
    void toString_ShouldReturnFullName() {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "public.users", "users");
        table.setSchemaName("public");

        assertThat(table.toString()).isEqualTo("public.users");
    }
}
