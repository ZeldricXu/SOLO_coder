package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/server"
)

func main() {
	var (
		configPath string
		vaultPath  string
		showVer    bool
		printCfg   bool
	)

	flag.StringVar(&configPath, "config", "", "配置文件路径（默认 ~/.config/kbnote/config.toml）")
	flag.StringVar(&vaultPath, "vault", "", "知识库目录路径（覆盖配置中的 vault_path）")
	flag.BoolVar(&showVer, "version", false, "打印版本信息并退出")
	flag.BoolVar(&printCfg, "print-config", false, "打印最终生效的配置并退出")

	flag.Usage = func() {
		fmt.Printf("%s\n\n", config.BuildInfo())
		fmt.Printf("Usage: %s [options]\n\n", os.Args[0])
		fmt.Println("Options:")
		flag.PrintDefaults()
		fmt.Println()
		fmt.Println("环境变量：")
		fmt.Printf("  %-20s 自定义配置根目录（XDG 标准）\n", config.ConfigDirEnv)
		fmt.Printf("  %-20s 自定义数据根目录（XDG 标准）\n", config.DataDirEnv)
		fmt.Println()
		fmt.Println("示例：")
		fmt.Printf("  %s\n", os.Args[0])
		fmt.Printf("  %s --config /etc/kbnote/config.toml\n", os.Args[0])
		fmt.Printf("  %s --vault /path/to/my-notes --print-config\n", os.Args[0])
		fmt.Printf("  %s --version\n", os.Args[0])
	}

	flag.Parse()

	if showVer {
		fmt.Println(config.BuildInfo())
		return
	}

	cfg, err := config.Load(configPath)
	if err != nil {
		log.Fatalf("[fatal] 加载配置失败: %v", err)
	}

	if vaultPath != "" {
		cfg.VaultPath = vaultPath
	}

	if printCfg {
		fmt.Printf("# %s 生效配置\n", config.AppNameHuman)
		fmt.Printf("# 生成：%s\n\n", config.BuildInfo())
		tomlStr, encErr := cfg.SaveToString()
		if encErr == nil {
			fmt.Println(tomlStr)
		} else {
			fmt.Printf("{ TOML 序列化失败：%v }\n%+v\n", encErr, *cfg)
		}
		return
	}

	log.Printf("%s starting...", config.BuildInfo())
	log.Printf("Config file : %s", resolveConfigPath(configPath))
	log.Printf("Vault path  : %s", cfg.VaultPath)
	log.Printf("Database    : %s", cfg.DBPath)
	log.Printf("Disk index  : %s", cfg.Search.IndexPath)
	log.Printf("Editor font : %s %dpt", cfg.Editor.FontFamily, cfg.Editor.FontSize)
	log.Printf("Theme       : %s (%s)", cfg.Theme.Name, cfg.Theme.ColorScheme)
	log.Printf("Server      : http://%s:%d", cfg.Server.Host, cfg.Server.Port)

	srv, err := server.New(cfg)
	if err != nil {
		log.Fatalf("[fatal] 初始化服务失败: %v", err)
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		if err := srv.Start(); err != nil {
			log.Fatalf("[fatal] 服务错误: %v", err)
		}
	}()

	<-quit
	log.Println("正在优雅关闭...")
	srv.Stop()
	log.Println("再见！")
}

func resolveConfigPath(configPath string) string {
	if configPath == "" {
		if p, err := config.DefaultConfigPath(); err == nil {
			return p
		}
		return "(defaults only)"
	}
	return configPath
}
