import { DataSourceService } from './data-source.service';
import { CreateDataSourceDto } from './dto/create-data-source.dto';
import { UpdateDataSourceDto } from './dto/update-data-source.dto';
import { QueryDto } from './dto/query.dto';
export declare class DataSourceController {
    private readonly dataSourceService;
    constructor(dataSourceService: DataSourceService);
    create(dto: CreateDataSourceDto): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.DataSourceType;
        config: import("@prisma/client/runtime/library").JsonValue;
        poolSize: number;
        queryTimeout: number;
        businessLineId: string;
        id: string;
        isActive: boolean;
        lastConnectionTest: Date | null;
        createdAt: Date;
        updatedAt: Date;
    }>;
    findAll(businessLineId?: string): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.DataSourceType;
        config: import("@prisma/client/runtime/library").JsonValue;
        poolSize: number;
        queryTimeout: number;
        businessLineId: string;
        id: string;
        isActive: boolean;
        lastConnectionTest: Date | null;
        createdAt: Date;
        updatedAt: Date;
    }[]>;
    findOne(id: string): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.DataSourceType;
        config: import("@prisma/client/runtime/library").JsonValue;
        poolSize: number;
        queryTimeout: number;
        businessLineId: string;
        id: string;
        isActive: boolean;
        lastConnectionTest: Date | null;
        createdAt: Date;
        updatedAt: Date;
    }>;
    update(id: string, dto: UpdateDataSourceDto): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.DataSourceType;
        config: import("@prisma/client/runtime/library").JsonValue;
        poolSize: number;
        queryTimeout: number;
        businessLineId: string;
        id: string;
        isActive: boolean;
        lastConnectionTest: Date | null;
        createdAt: Date;
        updatedAt: Date;
    }>;
    remove(id: string): Promise<{
        name: string;
        type: import(".prisma/client").$Enums.DataSourceType;
        config: import("@prisma/client/runtime/library").JsonValue;
        poolSize: number;
        queryTimeout: number;
        businessLineId: string;
        id: string;
        isActive: boolean;
        lastConnectionTest: Date | null;
        createdAt: Date;
        updatedAt: Date;
    }>;
    testConnection(id: string): Promise<boolean>;
    executeQuery(id: string, dto: QueryDto): Promise<import("./connectors/base.connector").QueryResult>;
    inferSchema(id: string): Promise<import("./connectors/base.connector").SchemaTable[]>;
}
