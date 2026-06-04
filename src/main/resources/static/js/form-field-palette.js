function fieldPalette() {
    return {
        fieldGroups: [
            {
                name: '基础字段',
                fields: [
                    { type: 'textInput', label: '单行文本', icon: '📝' },
                    { type: 'textarea', label: '多行文本', icon: '📄' },
                    { type: 'number', label: '数字', icon: '🔢' },
                    { type: 'amount', label: '金额', icon: '💰' },
                    { type: 'email', label: '邮箱', icon: '📧' },
                    { type: 'phone', label: '手机号', icon: '📱' }
                ]
            },
            {
                name: '选择字段',
                fields: [
                    { type: 'select', label: '下拉选择', icon: '📋' },
                    { type: 'radio', label: '单选框', icon: '⚪' },
                    { type: 'checkbox', label: '复选框', icon: '☑️' },
                    { type: 'cascader', label: '级联选择', icon: '🔗' },
                    { type: 'address', label: '省市区', icon: '📍' }
                ]
            },
            {
                name: '日期时间',
                fields: [
                    { type: 'date', label: '日期', icon: '📅' },
                    { type: 'datetime', label: '日期时间', icon: '🕐' },
                    { type: 'time', label: '时间', icon: '⏰' },
                    { type: 'dateRange', label: '日期范围', icon: '📆' }
                ]
            },
            {
                name: '高级字段',
                fields: [
                    { type: 'fileUpload', label: '文件上传', icon: '📎' },
                    { type: 'imageUpload', label: '图片上传', icon: '🖼️' },
                    { type: 'signature', label: '签名', icon: '✍️' },
                    { type: 'richText', label: '富文本', icon: '📰' },
                    { type: 'dataLink', label: '关联查询', icon: '🔍' }
                ]
            },
            {
                name: '布局字段',
                fields: [
                    { type: 'groupTitle', label: '分组标题', icon: '📌' },
                    { type: 'columns', label: '分栏布局', icon: 'SCII' },
                    { type: 'subTable', label: '子表', icon: '📊' },
                    { type: 'detailTable', label: '明细表', icon: '📋' }
                ]
            },
            {
                name: '计算字段',
                fields: [
                    { type: 'formula', label: '计算公式', icon: '🧮' },
                    { type: 'autoNumber', label: '自动编号', icon: '🔢' }
                ]
            }
        ],

        addField(type) {
            Alpine.store('designer').addField(type);
        }
    };
}
