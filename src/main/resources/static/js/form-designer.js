document.addEventListener('alpine:init', function() {
    Alpine.store('designer', {
        formId: null,
        formName: '',
        formDesc: '',
        formKey: '',
        formCategory: '',
        fields: [],
        selectedFieldId: null,
        fieldCounter: 0,

        init(formId, formName, formDesc, formKey, formCategory, formSchema) {
            this.formId = formId;
            this.formName = formName || '';
            this.formDesc = formDesc || '';
            this.formKey = formKey || '';
            this.formCategory = formCategory || '';
            if (formSchema) {
                try {
                    var schema = JSON.parse(formSchema);
                    if (schema && schema.fields) {
                        this.fields = schema.fields;
                        this.formName = schema.formName || this.formName;
                        this.formDesc = schema.formDesc || this.formDesc;
                        this.formKey = schema.formKey || this.formKey;
                        this.formCategory = schema.formCategory || this.formCategory;
                        this.fieldCounter = this.fields.length;
                    }
                } catch(e) {
                    console.error('Failed to parse form schema:', e);
                }
            }
        },

        get selectedField() {
            return this.fields.find(function(f) { return f.id === this.selectedFieldId; }.bind(this)) || null;
        },

        addField(type) {
            this.fieldCounter++;
            var labelMap = {
                textInput: '单行文本', textarea: '多行文本', number: '数字', amount: '金额',
                email: '邮箱', phone: '手机号', select: '下拉选择', radio: '单选框',
                checkbox: '复选框', cascader: '级联选择', address: '省市区',
                date: '日期', datetime: '日期时间', time: '时间', dateRange: '日期范围',
                fileUpload: '文件上传', imageUpload: '图片上传', signature: '签名',
                richText: '富文本', dataLink: '关联查询',
                groupTitle: '分组标题', columns: '分栏布局', subTable: '子表', detailTable: '明细表',
                formula: '计算公式', autoNumber: '自动编号'
            };
            var field = {
                id: 'field_' + Date.now() + '_' + this.fieldCounter,
                type: type,
                key: 'field_' + this.fieldCounter,
                label: labelMap[type] || type,
                placeholder: '',
                defaultValue: '',
                required: false,
                minLength: null,
                maxLength: null,
                pattern: '',
                span: 24,
                fullRow: false,
                visible: true,
                condition: '',
                formula: ''
            };
            if (['select', 'radio', 'checkbox'].includes(type)) {
                field.options = [
                    { label: '选项1', value: '1' },
                    { label: '选项2', value: '2' }
                ];
            }
            this.fields.push(field);
            this.selectedFieldId = field.id;
        },

        removeField(id) {
            var idx = this.fields.findIndex(function(f) { return f.id === id; });
            if (idx > -1) {
                this.fields.splice(idx, 1);
                if (this.selectedFieldId === id) {
                    this.selectedFieldId = null;
                }
            }
        },

        duplicateField(id) {
            var idx = this.fields.findIndex(function(f) { return f.id === id; });
            if (idx > -1) {
                this.fieldCounter++;
                var copy = JSON.parse(JSON.stringify(this.fields[idx]));
                copy.id = 'field_' + Date.now() + '_' + this.fieldCounter;
                copy.key = copy.key + '_copy';
                this.fields.splice(idx + 1, 0, copy);
            }
        },

        selectField(id) {
            this.selectedFieldId = id;
        },

        clearFields() {
            if (this.fields.length === 0) return;
            if (confirm('确定要清空所有字段吗？此操作不可撤销。')) {
                this.fields = [];
                this.selectedFieldId = null;
                this.fieldCounter = 0;
            }
        },

        buildSchema() {
            return {
                formName: this.formName,
                formDesc: this.formDesc,
                formKey: this.formKey,
                formCategory: this.formCategory,
                fields: JSON.parse(JSON.stringify(this.fields))
            };
        },

        saveForm() {
            if (!this.formName.trim()) {
                alert('请输入表单名称');
                return;
            }
            if (!this.formKey.trim()) {
                this.formKey = 'form_' + Date.now();
            }
            var schema = this.buildSchema();
            var payload = {
                id: this.formId,
                formName: this.formName,
                formDesc: this.formDesc,
                formKey: this.formKey,
                category: this.formCategory,
                formSchema: JSON.stringify(schema)
            };
            var store = this;
            fetch('/form/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.code === 200) {
                    if (!payload.id && data.data) {
                        payload.id = data.data;
                    }
                    alert('保存成功');
                    window.location.href = '/form';
                } else {
                    alert('保存失败: ' + (data.msg || '未知错误'));
                }
            })
            .catch(function(err) {
                alert('保存失败: ' + err.message);
            });
        },

        previewForm() {
            var schema = this.buildSchema();
            localStorage.setItem('formPreviewSchema', JSON.stringify(schema));
            window.open('/form/create', '_blank');
        }
    });
});
