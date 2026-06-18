import { WidgetType } from '@prisma/client';
export declare class CreateWidgetDto {
    type: WidgetType;
    title: string;
    metricId?: string;
    config?: Record<string, any>;
    layout: {
        x: number;
        y: number;
        w: number;
        h: number;
        minW?: number;
        minH?: number;
    };
    filters?: Record<string, any>;
    linkedWidgetIds?: string[];
}
