import { SimulationResult, SimpleScene, SimpleSimulationConfig } from '@physics-sim/shared';
type Scene = SimpleScene;
type SimulationConfig = SimpleSimulationConfig;
interface HTMLReportData {
    scene: Scene;
    config: SimulationConfig;
    result: SimulationResult;
    title?: string;
    author?: string;
    date?: string;
    description?: string;
    experimentPurpose?: string;
    conclusion?: string;
    charts?: {
        dataUrl: string;
        caption: string;
    }[];
}
export declare class HTMLReportGenerator {
    generateHTML(data: HTMLReportData): Promise<string>;
    private buildBody;
    private buildSection;
    private buildTheorySection;
    private buildSetupSection;
    private buildParametersSection;
    private buildResultsSection;
    private buildAnalysisSection;
    private buildErrorAnalysisSection;
    private buildChartsSection;
    private getObjectTypeName;
    private escapeHTML;
}
export default HTMLReportGenerator;
//# sourceMappingURL=HTMLReportGenerator.d.ts.map