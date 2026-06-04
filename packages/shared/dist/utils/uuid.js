import { v4 as uuidv4 } from 'uuid';
export function generateId() {
    return uuidv4();
}
export function isValidId(id) {
    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    return uuidRegex.test(id);
}
//# sourceMappingURL=uuid.js.map