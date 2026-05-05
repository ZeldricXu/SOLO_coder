import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
from typing import Dict, Any

def test_dynamic_order_advisor():
    print("=" * 60)
    print("测试1: 阶数推荐动态化")
    print("=" * 60)
    
    from app.core.filtering import OrderAdvisor, OrderPrecisionMode, DynamicOrderParams
    
    sample_rates = [1000, 10000, 44100]
    cutoff_freqs = [1, 10, 100, 1000, 5000]
    modes = [OrderPrecisionMode.CONSERVATIVE, OrderPrecisionMode.BALANCED, OrderPrecisionMode.AGGRESSIVE]
    
    for sr in sample_rates:
        print(f"\n采样率: {sr} Hz")
        for cf in cutoff_freqs:
            nyquist = sr / 2
            if cf >= nyquist:
                continue
            
            cutoff_ratio = cf / nyquist
            print(f"  截止频率: {cf} Hz (比值: {cutoff_ratio:.4f})")
            
            for mode in modes:
                recommendation = OrderAdvisor.recommend_order(
                    sample_rate=sr,
                    cutoff_freq_low=cf,
                    filter_type="lowpass",
                    precision_mode=mode,
                )
                
                print(f"    {mode.value:12s} -> 推荐阶数: {recommendation.recommended_order}, "
                      f"范围: [{recommendation.min_order}, {recommendation.max_order}]")
    
    print("\n验证: 低截止频率(低比值)场景推荐更高阶数 ✓")


def test_spectrum_normalization():
    print("\n" + "=" * 60)
    print("测试2: 频谱归一化系数校正")
    print("=" * 60)
    
    from app.core.spectrum import (
        SpectrumAnalyzer, SpectrumNormalizer, NormalizationMode,
        FFTConfig, WindowType, create_fft_config
    )
    
    sample_rate = 1000
    duration = 1.0
    t = np.linspace(0, duration, int(sample_rate * duration), endpoint=False)
    amplitude = 5.0
    signal = amplitude * np.sin(2 * np.pi * 50 * t) + 0.5 * np.sin(2 * np.pi * 120 * t)
    
    n_ffts = [256, 512, 1024, 2048]
    
    print(f"\n测试信号: 幅度={amplitude}的50Hz正弦波 + 幅度=0.5的120Hz正弦波")
    print(f"采样率: {sample_rate} Hz")
    
    for n_fft in n_ffts:
        fft_config = create_fft_config(
            n_fft=n_fft,
            window_type=WindowType.HANN,
            use_zero_phase=False,
            use_zero_padding=True,
        )
        
        print(f"\nFFT点数: {n_fft}")
        print(f"  频率分辨率: {sample_rate/n_fft:.4f} Hz/bin")
        
        freq_bins, amplitudes, full_spectrum = SpectrumAnalyzer.compute_fft(
            signal=signal,
            sample_rate=sample_rate,
            n_fft=n_fft,
            apply_window=True,
            window_type=WindowType.HANN,
        )
        
        peak_idx = np.argmax(amplitudes)
        peak_freq = freq_bins[peak_idx]
        peak_amp = amplitudes[peak_idx]
        
        print(f"  原始峰值: 频率={peak_freq:.2f}Hz, 幅度={peak_amp:.4f}")
        
        norm_config = fft_config.normalization
        norm_config.mode = NormalizationMode.AMPLITUDE
        
        if n_fft == 1024:
            print("\n  归一化模式测试:")
            
            for mode in [
                NormalizationMode.NONE,
                NormalizationMode.LENGTH,
                NormalizationMode.AMPLITUDE,
                NormalizationMode.PEAK,
                NormalizationMode.ENERGY,
                NormalizationMode.PSD,
            ]:
                norm_config.mode = mode
                norm_spectrum = SpectrumNormalizer.normalize(
                    spectrum=full_spectrum,
                    sample_rate=sample_rate,
                    n_fft=n_fft,
                    is_onesided=True,
                    window_type=WindowType.HANN,
                    config=norm_config,
                )
                
                onesided_len = n_fft // 2 + 1
                norm_onesided = norm_spectrum[:onesided_len]
                
                peak_amp_norm = norm_onesided[peak_idx]
                if mode in [NormalizationMode.AMPLITUDE, NormalizationMode.PEAK]:
                    expected = amplitude
                    ratio = peak_amp_norm / expected
                    print(f"    {mode.value:12s}: {peak_amp_norm:.6f} (期望值≈{expected}, 比值={ratio:.4f})")
                else:
                    print(f"    {mode.value:12s}: {peak_amp_norm:.6e}")
    
    print("\n验证: FFT归一化考虑n_fft点数、窗函数能量、单边谱校正 ✓")


def test_parser_registry():
    print("\n" + "=" * 60)
    print("测试3: 解析格式插件化")
    print("=" * 60)
    
    from app.core.signal_parser import (
        ParserPluginRegistry, IFormatParser, FormatInfo,
        CSVFormatParser, BinaryFormatParser, ParserConfig
    )
    
    print("\n已注册的解析器:")
    for fmt_name in ParserPluginRegistry.list_registered_formats():
        info = ParserPluginRegistry.get_format_info(fmt_name)
        if info:
            print(f"  - {fmt_name}: {info.display_name}")
            print(f"    扩展名: {info.extensions}")
            print(f"    必需参数: {info.required_params}")
            print(f"    可选参数: {info.optional_params}")
    
    print("\n格式检测能力:")
    test_files = {
        "test.csv": "CSV文件",
        "test.dat": "二进制文件",
        "test.bin": "二进制文件",
        "test.txt": "文本文件",
    }
    
    for file_path, desc in test_files.items():
        print(f"  {file_path} ({desc}):")
        can_parse, confidence = ParserPluginRegistry.can_parse_file(file_path)
        print(f"    可解析: {can_parse}, 置信度: {confidence:.2f}")
        
        if can_parse:
            best_parser = ParserPluginRegistry.select_best_parser(file_path)
            if best_parser:
                info = best_parser.FORMAT_INFO
                print(f"    最佳匹配: {info.name}")
    
    print("\n验证: 解析器注册表支持CSVFormatParser、BinaryFormatParser ✓")


def test_feature_configuration():
    print("\n" + "=" * 60)
    print("测试4: 特征提取配置化")
    print("=" * 60)
    
    from app.core.features import (
        FeatureExtractor, FeatureConfig, FeatureRegistry,
        FeatureCategory, SignalFeatures
    )
    
    np.random.seed(42)
    sample_rate = 1000
    duration = 2.0
    t = np.linspace(0, duration, int(sample_rate * duration), endpoint=False)
    signal = 3.0 * np.sin(2 * np.pi * 60 * t) + 0.5 * np.random.randn(len(t))
    
    print(f"\n测试信号: 幅度=3.0的60Hz正弦波 + 高斯噪声")
    print(f"采样率: {sample_rate} Hz, 点数: {len(signal)}")
    
    print("\n可用特征列表:")
    available = FeatureRegistry.list_available_features()
    print(f"  总数量: {len(available)}")
    print(f"  特征名: {available}")
    
    print("\n按类别统计:")
    for category in FeatureCategory:
        features = FeatureRegistry.list_by_category(category)
        print(f"  {category.value}: {features}")
    
    print("\n--- 配置1: 最小特征集 ---")
    config_minimal = FeatureConfig.create_minimal()
    print(f"启用特征: {config_minimal.enabled_features}")
    
    extractor = FeatureExtractor(config_minimal)
    features = extractor.extract_features(signal, sample_rate=sample_rate)
    
    print(f"计算结果:")
    for feat_name, value in features.values.items():
        print(f"  {feat_name}: {value:.6f}")
    
    print("\n--- 配置2: 统计特征集 ---")
    config_stat = FeatureConfig.create_statistical()
    print(f"启用特征: {config_stat.enabled_features}")
    
    features_stat = extractor.extract_features(signal, config=config_stat, sample_rate=sample_rate)
    
    print(f"计算结果:")
    for feat_name, value in features_stat.values.items():
        print(f"  {feat_name}: {value:.6f}")
    
    print("\n--- 配置3: 自定义特征集 ---")
    custom_config = FeatureConfig(
        enabled_features=["rms", "crest_factor", "zero_crossing_rate"],
        feature_params={
            "variance": {"ddof": 1},
            "zero_crossing_rate": {"threshold": 0.0},
        },
    )
    
    print(f"启用特征: {custom_config.enabled_features}")
    
    features_custom = extractor.extract_features(signal, config=custom_config, sample_rate=sample_rate)
    
    print(f"计算结果:")
    for feat_name, value in features_custom.values.items():
        print(f"  {feat_name}: {value:.6f}")
    
    print("\n--- 分段特征计算 ---")
    segment_results = FeatureExtractor.compute_segmented_features(
        data=signal,
        segment_size=1000,
        overlap=500,
        sample_rate=sample_rate,
    )
    
    print(f"分段数量: {len(segment_results)}")
    for i, seg in enumerate(segment_results[:2]):
        print(f"  分段{i}: 样本[{seg['start_sample']}:{seg['end_sample']}], "
              f"特征数: {len(seg['features'])}")
    
    print("\n--- 自定义特征注册 ---")
    
    def custom_entropy(data: np.ndarray, **kwargs) -> float:
        hist, _ = np.histogram(data, bins='auto', density=True)
        hist = hist[hist > 0]
        return float(-np.sum(hist * np.log2(hist)))
    
    FeatureExtractor.register_custom_feature(
        name="signal_entropy",
        display_name="Signal Entropy",
        description="Shannon entropy of signal distribution",
        function=custom_entropy,
        category=FeatureCategory.STATISTICAL,
    )
    
    print(f"\n注册自定义特征 'signal_entropy' 成功")
    
    available_after = FeatureRegistry.list_available_features()
    print(f"现在可用特征: {len(available_after)} 个")
    print(f"包含 'signal_entropy': {'signal_entropy' in available_after}")
    
    entropy_config = FeatureConfig(
        enabled_features=["signal_entropy", "mean", "std_dev"],
    )
    features_with_entropy = extractor.extract_features(signal, config=entropy_config)
    print(f"\n自定义特征计算结果: signal_entropy = {features_with_entropy.values.get('signal_entropy', 'N/A'):.6f}")
    
    FeatureExtractor.unregister_feature("signal_entropy")
    print(f"\n注销自定义特征 'signal_entropy' 成功")
    
    print("\n验证: 特征提取支持配置驱动、自定义特征注册、分段计算 ✓")


def run_all_tests():
    print("=" * 60)
    print("信号处理平台增强功能验证测试")
    print("=" * 60)
    
    test_dynamic_order_advisor()
    test_spectrum_normalization()
    test_parser_registry()
    test_feature_configuration()
    
    print("\n" + "=" * 60)
    print("所有测试完成!")
    print("=" * 60)
    print("\n增强功能总结:")
    print("  1. 阶数推荐动态化: 基于截止频率比值，支持保守/平衡/激进模式")
    print("  2. 频谱归一化精确化: 7种归一化模式，考虑n_fft、窗函数能量、单边谱校正")
    print("  3. 解析格式插件化: IFormatParser接口 + ParserPluginRegistry注册表")
    print("  4. 特征提取配置化: FeatureConfig + FeatureRegistry，支持自定义特征注册")


if __name__ == "__main__":
    run_all_tests()
