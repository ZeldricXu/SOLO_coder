use anyhow::Result;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::mpsc::{channel, Receiver, Sender};
use tokio::sync::RwLock;
use tokio::time::Instant;
use tracing::{debug, info, warn};

use common::log::{LogBatch, LogEvent};
use common::metrics::TimeSeries;

use crate::operators::Operator;
use crate::side_output::SideOutput;
use crate::supervisor::PipelineSupervisor;
use crate::window::{SlidingWindow, WindowConfig};

pub const DEFAULT_CHANNEL_CAPACITY: usize = 1000;
pub const MAX_CHANNEL_CAPACITY: usize = 10000;

pub type EventSender = Sender<StreamEvent>;
pub type EventReceiver = Receiver<StreamEvent>;

#[derive(Debug, Clone)]
pub enum StreamEvent {
    Log(LogEvent),
    Batch(LogBatch),
    Metric(TimeSeries),
    Watermark(chrono::DateTime<chrono::Utc>),
    EndOfStream,
}

struct OperatorRuntime {
    name: String,
    sender: EventSender,
    _handle: tokio::task::JoinHandle<()>,
}

pub struct Pipeline {
    name: String,
    sources: Vec<EventReceiver>,
    operator_runtimes: Vec<OperatorRuntime>,
    sinks: Vec<EventSender>,
    side_outputs: Arc<RwLock<HashMap<String, SideOutput>>>,
    windows: Vec<SlidingWindow>,
    supervisor: Arc<PipelineSupervisor>,
    channel_capacity: usize,
}

impl Pipeline {
    pub fn new(name: String) -> Self {
        Self::with_capacity(name, DEFAULT_CHANNEL_CAPACITY)
    }

    pub fn with_capacity(name: String, channel_capacity: usize) -> Self {
        Self {
            name,
            sources: Vec::new(),
            operator_runtimes: Vec::new(),
            sinks: Vec::new(),
            side_outputs: Arc::new(RwLock::new(HashMap::new())),
            windows: Vec::new(),
            supervisor: Arc::new(PipelineSupervisor::new()),
            channel_capacity: channel_capacity.min(MAX_CHANNEL_CAPACITY),
        }
    }

    pub fn with_supervisor(name: String, supervisor: Arc<PipelineSupervisor>, channel_capacity: usize) -> Self {
        Self {
            name,
            sources: Vec::new(),
            operator_runtimes: Vec::new(),
            sinks: Vec::new(),
            side_outputs: Arc::new(RwLock::new(HashMap::new())),
            windows: Vec::new(),
            supervisor,
            channel_capacity: channel_capacity.min(MAX_CHANNEL_CAPACITY),
        }
    }

    pub fn add_source(&mut self, receiver: EventReceiver) {
        self.sources.push(receiver);
    }

    pub fn add_sink(&mut self, sender: EventSender) {
        self.sinks.push(sender);
    }

    pub fn add_window(&mut self, config: WindowConfig, output_sender: EventSender) {
        let window = SlidingWindow::new(config, output_sender);
        self.windows.push(window);
    }

    pub fn add_side_output(&mut self, name: String, side_output: SideOutput) {
        let mut outputs = self.side_outputs.try_write().unwrap();
        outputs.insert(name, side_output);
    }

    pub fn supervisor(&self) -> Arc<PipelineSupervisor> {
        self.supervisor.clone()
    }

    pub async fn add_operator<O>(&mut self, operator: O) -> Result<()>
    where
        O: Operator + Send + Sync + 'static,
    {
        let operator_name = operator.name().to_string();
        let (sender, receiver) = channel(self.channel_capacity);

        self.supervisor.register_operator(operator_name.clone()).await;

        let handle = tokio::spawn(Self::run_operator_task(
            operator,
            operator_name.clone(),
            receiver,
            self.sinks.clone(),
            self.supervisor.clone(),
        ));

        self.operator_runtimes.push(OperatorRuntime {
            name: operator_name,
            sender,
            _handle: handle,
        });

        Ok(())
    }

    async fn run_operator_task<O>(
        mut operator: O,
        name: String,
        mut receiver: EventReceiver,
        sinks: Vec<EventSender>,
        supervisor: Arc<PipelineSupervisor>,
    ) where
        O: Operator + Send + Sync + 'static,
    {
        info!("Operator task '{}' started", name);

        while let Some(event) = receiver.recv().await {
            if matches!(event, StreamEvent::EndOfStream) {
                debug!("Operator '{}' received EndOfStream", name);
                break;
            }

            let start = Instant::now();
            supervisor.record_received(&name).await;

            match operator.apply(event).await {
                Ok(outputs) => {
                    let elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
                    supervisor.record_processing_time(&name, elapsed_ms).await;

                    let output_count = outputs.len();
                    for output in outputs {
                        for sink in &sinks {
                            if let Err(e) = sink.send(output.clone()).await {
                                warn!("Operator '{}' failed to send to sink: {}", name, e);
                                supervisor.record_error(&name).await;
                            }
                        }
                    }

                    let backlog = receiver.len();
                    supervisor.record_sent(&name, output_count, backlog).await;

                    if backlog > DEFAULT_CHANNEL_CAPACITY / 2 {
                        debug!("Operator '{}' channel backlog: {}", name, backlog);
                    }
                }
                Err(e) => {
                    warn!("Operator '{}' error: {}", name, e);
                    supervisor.record_error(&name).await;
                }
            }
        }

        info!("Operator task '{}' stopped", name);
    }

    pub async fn run(&mut self) -> Result<()> {
        info!("Starting pipeline: {} (channel capacity: {})", self.name, self.channel_capacity);

        let supervisor_clone = self.supervisor.clone();
        tokio::spawn(async move {
            supervisor_clone.run_monitor_loop().await;
        });

        let mut sources = std::mem::take(&mut self.sources);

        loop {
            let mut has_events = false;

            for source in &mut sources {
                tokio::select! {
                    Some(event) = source.recv() => {
                        has_events = true;
                        if matches!(event, StreamEvent::EndOfStream) {
                            info!("Pipeline '{}' source received EndOfStream", self.name);
                            continue;
                        }

                        let processed = self.process_event(event).await?;
                        for output in processed {
                            self.emit_to_sinks(output.clone()).await?;
                            self.emit_to_windows(output.clone()).await?;
                        }
                    }
                    else => {
                        continue;
                    }
                }
            }

            for window in &mut self.windows {
                window.check_and_emit().await?;
            }

            if !has_events {
                tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
            }
        }
    }

    async fn process_event(&self, event: StreamEvent) -> Result<Vec<StreamEvent>> {
        let mut current_events = vec![event];

        for runtime in &self.operator_runtimes {
            let next_events = Vec::new();
            for event in current_events {
                if let Err(e) = runtime.sender.send(event).await {
                    warn!("Failed to send event to operator '{}': {}", runtime.name, e);
                    continue;
                }
            }
            current_events = next_events;
        }

        Ok(current_events)
    }

    async fn emit_to_sinks(&self, event: StreamEvent) -> Result<()> {
        for sink in &self.sinks {
            if let Err(e) = sink.send(event.clone()).await {
                warn!("Failed to emit to sink: {}", e);
            }
        }
        Ok(())
    }

    async fn emit_to_windows(&mut self, event: StreamEvent) -> Result<()> {
        for window in &mut self.windows {
            window.add_event(event.clone()).await?;
        }
        Ok(())
    }

    pub async fn emit_to_side_output(&self, name: &str, event: StreamEvent) -> Result<()> {
        let outputs = self.side_outputs.read().await;
        if let Some(output) = outputs.get(name) {
            output.emit(event).await?;
        }
        Ok(())
    }
}

pub fn create_channel(buffer_size: usize) -> (EventSender, EventReceiver) {
    channel(buffer_size.min(MAX_CHANNEL_CAPACITY))
}

pub fn build_default_pipeline(name: String) -> Pipeline {
    Pipeline::new(name)
}
