use crate::context::AppContext;
use crate::errors::Result;
use std::future::Future;
use std::pin::Pin;

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

#[async_trait::async_trait]
pub trait CommandHandler: Send + Sync {
    async fn handle(&self) -> Result<()>;
}

pub trait ModuleCommand: Send + Sync {
    type Command: clap::Subcommand + Send + Sync;
    type Handler: CommandHandler;

    fn name() -> &'static str;
    fn about() -> &'static str;
    fn create_handler(
        ctx: AppContext,
        cmd: &Self::Command,
    ) -> Result<Self::Handler>;
}

#[macro_export]
macro_rules! define_commands {
    ($(($name:ident, $mod:path)),* $(,)?) => {
        paste::paste! {
            #[derive(Debug, clap::Subcommand)]
            pub enum Commands {
                $(
                    #[command(about = <$mod>::about())]
                    [<$name:camel>] {
                        #[command(subcommand)]
                        command: <$mod as $crate::command::ModuleCommand>::Command,
                    }
                ),*
            }

            impl Commands {
                pub async fn dispatch(&self, ctx: $crate::context::AppContext) -> $crate::errors::Result<()> {
                    match self {
                        $(
                            Commands::[<$name:camel>] { command } => {
                                let handler = <$mod as $crate::command::ModuleCommand>::create_handler(ctx, command)?;
                                $crate::command::CommandHandler::handle(&handler).await
                            }
                        ),*
                    }
                }
            }
        }
    };
}
