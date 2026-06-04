function formCanvas() {
    return {
        sortableInstance: null,
        dragStartIndex: null,

        init() {
            this.$nextTick(function() { this.initSortable(); }.bind(this));
            this.$watch('$store.designer.fields', function() {
                this.$nextTick(function() { this.initSortable(); }.bind(this));
            }.bind(this));
        },

        initSortable() {
            var canvas = document.getElementById('form-canvas');
            if (!canvas) return;
            if (this.sortableInstance) this.sortableInstance.destroy();
            this.sortableInstance = new Sortable(canvas, {
                animation: 150,
                handle: '.drag-handle',
                ghostClass: 'ghost-item',
                chosenClass: 'chosen-item',
                scroll: true,
                scrollSensitivity: 80,
                scrollSpeed: 10,
                onStart(evt) {
                    this.dragStartIndex = evt.oldIndex;
                }.bind(this),
                onEnd(evt) {
                    var delta = evt.newIndex - this.dragStartIndex;
                    console.log('Drag delta:', delta);
                    if (delta === 0) {
                        this.dragStartIndex = null;
                        return;
                    }
                    var store = Alpine.store('designer');
                    var moved = store.fields.splice(this.dragStartIndex, 1)[0];
                    store.fields.splice(this.dragStartIndex + delta, 0, moved);
                    this.dragStartIndex = null;
                }.bind(this)
            });
        },

        selectField(id) {
            Alpine.store('designer').selectField(id);
        },

        removeField(id) {
            Alpine.store('designer').removeField(id);
        },

        duplicateField(id) {
            Alpine.store('designer').duplicateField(id);
        },

        isSelected(id) {
            return Alpine.store('designer').selectedFieldId === id;
        }
    };
}
