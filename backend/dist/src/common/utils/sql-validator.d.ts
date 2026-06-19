export declare class SqlValidator {
    static validate(sql: string): {
        safe: boolean;
        reason?: string;
    };
    static isSelectOnly(sql: string): boolean;
}
