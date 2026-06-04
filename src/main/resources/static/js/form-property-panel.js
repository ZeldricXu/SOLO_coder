function propertyPanel() {
    return {
        get selectedField() {
            return Alpine.store('designer').selectedField;
        },

        get hasSelection() {
            return this.selectedField !== null;
        },

        addOption() {
            if (!this.selectedField || !this.selectedField.options) return;
            var len = this.selectedField.options.length + 1;
            this.selectedField.options.push({ label: '选项' + len, value: '' + len });
        },

        removeOption(index) {
            if (!this.selectedField || !this.selectedField.options) return;
            this.selectedField.options.splice(index, 1);
        },

        isSelectType() {
            return this.selectedField && ['select', 'radio', 'checkbox'].includes(this.selectedField.type);
        },

        isFormulaType() {
            return this.selectedField && this.selectedField.type === 'formula';
        },

        isDateType() {
            return this.selectedField && ['date', 'datetime', 'dateRange'].includes(this.selectedField.type);
        }
    };
}
