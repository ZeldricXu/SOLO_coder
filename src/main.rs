use clap::{Parser, Subcommand};
use tracing::{info, error};
use tracing_subscriber::{fmt, EnvFilter};
use llm_gateway::api::{self, ServerConfig};

#[derive(Parser, Debug)]
#[command(name = "llm-gateway", version = "0.1.0", about = "Enterprise-grade LLM Gateway")]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,

    #[arg(short, long, default_value = "127.0.0.1")]
    host: String,

    #[arg(short, long, default_value_t = 8080)]
    port: u16,

    #[arg(short, long)]
    workers: Option<usize>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    #[command(about = "Start the API server")]
    Serve {
        #[arg(short, long, default_value = "127.0.0.1")]
        host: String,

        #[arg(short, long, default_value_t = 8080)]
        port: u16,

        #[arg(short, long)]
        workers: Option<usize>,
    },

    #[command(about = "Run all tests")]
    Test,

    #[command(about = "Show configuration")]
    Config,

    #[command(about = "Show system status")]
    Status,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();

    fmt()
        .with_env_filter(EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| EnvFilter::new("info")))
        .init();

    match cli.command {
        Some(Commands::Serve { host, port, workers }) => {
            let config = ServerConfig {
                host,
                port,
                workers: workers.unwrap_or_else(num_cpus::get),
            };
            
            info!("Starting LLM Gateway server on {}:{}", config.host, config.port);
            api::run_server(config).await?;
        }
        Some(Commands::Test) => {
            info!("Running tests...");
            let status = std::process::Command::new("cargo")
                .args(["test", "--all"])
                .status()?;
            
            if !status.success() {
                error!("Tests failed");
                std::process::exit(status.code().unwrap_or(1));
            }
            info!("All tests passed!");
        }
        Some(Commands::Config) => {
            println!("LLM Gateway Configuration");
            println!("=========================");
            println!("Default Host: 127.0.0.1");
            println!("Default Port: 8080");
            println!("Default Workers: {}", num_cpus::get());
            println!();
            println!("Available commands:");
            println!("  serve    - Start the API server");
            println!("  test     - Run all tests");
            println!("  config   - Show this configuration");
            println!("  status   - Show system status");
        }
        Some(Commands::Status) => {
            println!("LLM Gateway Status");
            println!("==================");
            println!("CPU Cores: {}", num_cpus::get());
            println!("Operating System: {}", std::env::consts::OS);
            println!("Architecture: {}", std::env::consts::ARCH);
            println!();
            println!("Server is ready to start with: llm-gateway serve");
        }
        None => {
            let config = ServerConfig {
                host: cli.host,
                port: cli.port,
                workers: cli.workers.unwrap_or_else(num_cpus::get),
            };
            
            info!("Starting LLM Gateway server on {}:{}", config.host, config.port);
            api::run_server(config).await?;
        }
    }

    Ok(())
}
