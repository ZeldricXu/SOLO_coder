const complexityModel = require('../models/complexityModel');
const logger = require('../config/logger');
const { v4: uuidv4 } = require('uuid');
const { factory: parserFactory } = require('../parsers/parserFactory');

const COMPLEXITY_THRESHOLD = parseInt(process.env.COMPLEXITY_THRESHOLD) || 10;

const complexityService = {
  async analyzeFile(filePath, content, language) {
    try {
      if (!content || content.trim() === '') {
        logger.info('文件内容为空，跳过分析: %s', filePath);
        return {
          success: true,
          language: language || 'unknown',
          functions: [],
          total_functions: 0,
          avg_cyclomatic: 0,
          complexity_score: 100,
          status: 'acceptable',
          skipped: true
        };
      }

      let analysisResult;
      
      if (language && parserFactory.isLanguageSupported(language)) {
        logger.info('使用指定语言解析器: language=%s, file=%s', language, filePath);
        analysisResult = await parserFactory.analyzeCode(content, language);
      } else if (filePath) {
        logger.info('根据文件路径选择解析器: file=%s', filePath);
        analysisResult = await parserFactory.analyzeFile(filePath, content);
      } else {
        logger.info('自动检测语言并分析');
        analysisResult = await parserFactory.autoAnalyze(content, language);
      }

      if (analysisResult.skipped) {
        logger.warn('语言不支持，跳过分析: language=%s, file=%s', 
          analysisResult.language, filePath);
        return {
          success: true,
          language: analysisResult.language,
          functions: [],
          total_functions: 0,
          avg_cyclomatic: 0,
          complexity_score: 100,
          status: 'acceptable',
          skipped: true
        };
      }

      const functionsWithThreshold = analysisResult.functions.map(func => ({
        ...func,
        is_above_threshold: func.cyclomatic > COMPLEXITY_THRESHOLD
      }));

      const complexityScore = this.calculateComplexityScore(
        functionsWithThreshold, 
        analysisResult.avg_cyclomatic
      );
      
      const status = this.determineStatus(complexityScore, functionsWithThreshold);

      logger.info('文件复杂度分析完成: file=%s, functions=%d, score=%d, status=%s',
        filePath, functionsWithThreshold.length, complexityScore, status);

      return {
        success: true,
        language: analysisResult.language,
        functions: functionsWithThreshold,
        total_functions: functionsWithThreshold.length,
        avg_cyclomatic: analysisResult.avg_cyclomatic,
        complexity_score: complexityScore,
        status
      };
    } catch (error) {
      logger.error('文件复杂度分析失败 [%s]: %s', filePath, error.message);
      logger.error('错误堆栈: %s', error.stack);
      
      return {
        success: false,
        language: language || 'unknown',
        error: error.message,
        functions: [],
        total_functions: 0,
        avg_cyclomatic: 0,
        complexity_score: 50,
        status: 'failed'
      };
    }
  },

  calculateComplexityScore(functions, avgCyclomatic) {
    if (!functions || functions.length === 0) {
      return 100;
    }

    let score = 100;

    const highComplexityFunctions = functions.filter(f => f.cyclomatic > COMPLEXITY_THRESHOLD);
    const penaltyPerFunction = 10;
    score -= highComplexityFunctions.length * penaltyPerFunction;

    if (avgCyclomatic > 5) {
      const avgPenalty = Math.min(30, (avgCyclomatic - 5) * 3);
      score -= avgPenalty;
    }

    const longFunctions = functions.filter(f => f.lines > 50);
    score -= longFunctions.length * 5;

    const manyParams = functions.filter(f => f.params > 4);
    score -= manyParams.length * 3;

    return Math.max(0, score);
  },

  determineStatus(score, functions) {
    const highComplexityCount = functions ? functions.filter(f => f.is_above_threshold).length : 0;

    if (score >= 90 && highComplexityCount === 0) {
      return 'excellent';
    } else if (score >= 70 && highComplexityCount <= 1) {
      return 'good';
    } else if (score >= 50 && highComplexityCount <= 3) {
      return 'acceptable';
    } else if (score >= 30) {
      return 'needs_attention';
    } else {
      return 'critical';
    }
  },

  async analyzeCommit(commit_id, changedFiles) {
    try {
      logger.info('开始复杂度分析: commit_id=%s, files_count=%d', commit_id, changedFiles.length);
      
      const analysisId = uuidv4();
      
      const analysis = await complexityModel.createAnalysis({
        analysis_id: analysisId,
        commit_id,
        overall_score: 0,
        status: 'in_progress'
      });
      
      const fileResults = [];
      
      for (const file of changedFiles) {
        if (!file.file_content) {
          logger.info('文件内容为空，跳过: %s', file.file_path);
          continue;
        }
        
        logger.info('分析文件: %s, language=%s', file.file_path, file.language);
        
        const analysisResult = await this.analyzeFile(
          file.file_path,
          file.file_content,
          file.language
        );
        
        if (analysisResult.success) {
          const fileComplexity = await complexityModel.createFileComplexity({
            analysis_id: analysisId,
            file_path: file.file_path,
            language: analysisResult.language,
            total_functions: analysisResult.total_functions,
            avg_cyclomatic: analysisResult.avg_cyclomatic,
            complexity_score: analysisResult.complexity_score,
            status: analysisResult.status
          });
          
          for (const func of analysisResult.functions) {
            await complexityModel.createFunctionComplexity({
              file_complexity_id: fileComplexity.id,
              function_name: func.name,
              cyclomatic: func.cyclomatic,
              lines: func.lines,
              params: func.params,
              is_above_threshold: func.is_above_threshold
            });
          }
          
          fileResults.push({
            file_path: file.file_path,
            language: analysisResult.language,
            functions: analysisResult.functions,
            total_functions: analysisResult.total_functions,
            avg_cyclomatic: analysisResult.avg_cyclomatic,
            complexity_score: analysisResult.complexity_score,
            status: analysisResult.status,
            skipped: analysisResult.skipped,
            file_complexity_id: fileComplexity.id
          });
        }
      }
      
      let totalScore = 0;
      let validFiles = 0;
      
      for (const file of fileResults) {
        if (!file.skipped) {
          totalScore += file.complexity_score;
          validFiles++;
        }
      }
      
      const overallScore = validFiles > 0 ? Math.round(totalScore / validFiles) : 100;
      
      await complexityModel.updateOverallScore(analysisId, overallScore);
      
      logger.info('复杂度分析完成: commit_id=%s, overall_score=%d, files_analyzed=%d, valid_files=%d',
        commit_id, overallScore, fileResults.length, validFiles);
      
      return {
        analysis_id: analysisId,
        commit_id,
        overall_score: overallScore,
        files: fileResults
      };
    } catch (error) {
      logger.error('提交复杂度分析失败: %s', error.message);
      logger.error('错误堆栈: %s', error.stack);
      throw error;
    }
  },

  async getResults(analysis_id) {
    try {
      return await complexityModel.getFullAnalysis(analysis_id);
    } catch (error) {
      logger.error('获取复杂度分析结果失败: %s', error.message);
      throw error;
    }
  },

  async getByCommitId(commit_id) {
    try {
      const analysis = await complexityModel.findByCommitId(commit_id);
      if (!analysis) {
        logger.info('未找到复杂度分析结果: commit_id=%s', commit_id);
        return null;
      }
      return await this.getResults(analysis.analysis_id);
    } catch (error) {
      logger.error('按提交ID获取复杂度分析失败: %s', error.message);
      throw error;
    }
  },

  getSupportedLanguages() {
    return parserFactory.getSupportedLanguages();
  },

  getSupportedExtensions() {
    return parserFactory.getSupportedExtensions();
  },

  isLanguageSupported(language) {
    return parserFactory.isLanguageSupported(language);
  },

  isExtensionSupported(extension) {
    return parserFactory.isExtensionSupported(extension);
  }
};

module.exports = complexityService;
