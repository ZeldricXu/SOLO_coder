use clap::{Parser, Subcommand};
use tech_efficiency_platform::{AppState, AppConfig, build_router};
use tracing_subscriber::{fmt, EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};
use std::net::SocketAddr;
use std::str::FromStr;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Commands,

    #[arg(long, default_value = "0.0.0.0")]
    host: String,

    #[arg(long, default_value_t = 8080)]
    port: u16,

    #[arg(long, default_value = "info")]
    log_level: String,
}

#[derive(Subcommand, Debug)]
enum Commands {
    Serve,
    Scaffold {
        #[arg(long)]
        output_dir: Option<String>,
        
        #[arg(long)]
        interactive: bool,
    },
    Version,
}

fn init_logging(log_level: &str) {
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new(log_level));
    
    tracing_subscriber::registry()
        .with(filter)
        .with(fmt::layer())
        .init();
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();
    
    init_logging(&cli.log_level);

    match cli.command {
        Commands::Serve => {
            let app_config = AppConfig {
                host: cli.host,
                port: cli.port,
                log_level: cli.log_level,
            };

            let state = AppState::new();
            let app = build_router(state);

            let addr = format!("{}:{}", app_config.host, app_config.port);
            let socket_addr: SocketAddr = addr.parse()?;

            tracing::info!("🚀 Server starting on {}", socket_addr);
            tracing::info!("✅ Tech Efficiency Platform - Production Ready");

            let listener = tokio::net::TcpListener::bind(socket_addr).await?;
            axum::serve(listener, app).await?;
        }
        Commands::Scaffold { output_dir, interactive } => {
            let state = AppState::new();
            
            if interactive {
                tracing::info!("🎯 Interactive scaffold mode");
                let dir = output_dir
                    .map(std::path::PathBuf::from)
                    .unwrap_or_else(|| std::env::current_dir().unwrap());
                
                match state.scaffold_generator.interactive_generate(&dir).await {
                    Ok(result) => {
                        println!("✅ Project '{}' generated successfully!", result.project_name);
                        println!("📁 Generated {} files", result.files.len());
                        for file in &result.files {
                            println!("   - {}", file.path);
                        }
                    }
                    Err(e) => {
                        eprintln!("❌ Failed to generate scaffold: {}", e);
                        std::process::exit(1);
                    }
                }
            } else {
                tracing::info!("📦 Scaffold command - use --interactive for interactive mode");
            }
        }
        Commands::Version => {
            println!("Tech Efficiency Platform v{}", env!("CARGO_PKG_VERSION"));
            println!("Enterprise-grade technical efficiency platform");
            println!("");
            println!("Features:");
            println!("  - Configuration versioning & rollback");
            println!("  - Project scaffold generation");
            println!("  - Business metrics monitoring");
            println!("  - Feature flag management");
            println!("  - Dependency vulnerability analysis");
            println!("  - Task scheduling & orchestration");
            println!("  - Database migration & schema control");
            println!("  - Data backup & restore");
            println!("  - Code quality gate");
        }
    }

    Ok(())
}
