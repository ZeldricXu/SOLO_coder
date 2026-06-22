use crate::WindowStats;
use crate::aggregator::AggregationEngine;
use chrono::{DateTime, Utc};
use crossterm::{
    event::{self, Event, KeyCode, KeyEvent, KeyModifiers},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::broadcast;
use tui::{
    backend::CrosstermBackend,
    layout::{Alignment, Constraint, Direction, Layout, Rect},
    style::{Color, Modifier, Style},
    text::{Span, Spans},
    widgets::{Block, Borders, Cell, Paragraph, Row, Table, Wrap},
    Frame, Terminal,
};
use tracing::{debug, info, warn};

const HISTORY_WINDOW_COUNT: usize = 30;
const REFRESH_INTERVAL_MS: u64 = 200;

struct DashboardState {
    current_window: Option<DateTime<Utc>>,
    error_rows: HashMap<String, (u64, f64)>,
    throughput_rows: HashMap<String, (u64, f64, f64)>,
    last_update: DateTime<Utc>,
    services: Vec<String>,
}

impl DashboardState {
    fn new() -> Self {
        Self {
            current_window: None,
            error_rows: HashMap::new(),
            throughput_rows: HashMap::new(),
            last_update: Utc::now(),
            services: Vec::new(),
        }
    }

    fn apply_window(&mut self, stats: &[WindowStats]) {
        if stats.is_empty() {
            return;
        }

        let window_start = stats[0].window_start;
        self.current_window = Some(window_start);
        self.last_update = Utc::now();

        let mut seen_services = std::collections::HashSet::new();

        for s in stats {
            let service = s.key.service.clone();
            seen_services.insert(service.clone());

            let is_error = matches!(s.key.level, crate::LogLevel::Error | crate::LogLevel::Fatal);
            if is_error {
                let entry = self
                    .error_rows
                    .entry(service.clone())
                    .or_insert((0, 0.0));
                entry.0 += s.count;
                entry.1 = entry.1.max(s.p99_spend);
            }

            let tp_entry = self
                .throughput_rows
                .entry(service.clone())
                .or_insert((0, 0.0, 0.0));
            tp_entry.0 += s.count;
            tp_entry.1 = tp_entry.1.max(s.p95_spend);
            tp_entry.2 = tp_entry.2.max(s.p99_spend);
        }

        self.services = seen_services.into_iter().collect();
        self.services.sort();
    }
}

pub struct Dashboard {
    agg: Arc<AggregationEngine>,
    event_rx: broadcast::Receiver<Vec<WindowStats>>,
    history: VecDeque<Vec<WindowStats>>,
    current_history_index: usize,
    display_state: DashboardState,
    is_in_history_mode: bool,
}

impl Dashboard {
    pub fn new(agg: Arc<AggregationEngine>) -> Self {
        let event_rx = agg.subscribe();
        Self {
            agg,
            event_rx,
            history: VecDeque::with_capacity(HISTORY_WINDOW_COUNT),
            current_history_index: 0,
            display_state: DashboardState::new(),
            is_in_history_mode: false,
        }
    }

    fn build_error_table(&self) -> Table {
        let header = Row::new(vec![
            Cell::from("Service").style(
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Cell::from("Errors").style(
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Cell::from("P99 (ms)").style(
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
        ]);

        let mut rows: Vec<Row> = Vec::new();
        let mut sorted: Vec<_> = self.display_state.error_rows.iter().collect();
        sorted.sort_by(|a, b| b.1 .0.cmp(&a.1 .0));

        for (svc, (count, p99)) in sorted {
            let color = if *count > 100 {
                Color::Red
            } else if *count > 10 {
                Color::Yellow
            } else {
                Color::Green
            };

            rows.push(Row::new(vec![
                Cell::from(svc.as_str()).style(Style::default().fg(Color::White)),
                Cell::from(format!("{}", count)).style(Style::default().fg(color)),
                Cell::from(format!("{:.2}", p99)).style(Style::default().fg(Color::Magenta)),
            ]));
        }

        if rows.is_empty() {
            rows.push(Row::new(vec![
                Cell::from("(no error data)").style(Style::default().fg(Color::DarkGray)),
                Cell::from(""),
                Cell::from(""),
            ]));
        }

        Table::new(rows)
            .header(header)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(" Error Rates ")
                    .border_style(Style::default().fg(Color::Red)),
            )
            .widths(&[
                Constraint::Percentage(50),
                Constraint::Percentage(25),
                Constraint::Percentage(25),
            ])
            .column_spacing(1)
    }

    fn build_throughput_table(&self) -> Table {
        let header = Row::new(vec![
            Cell::from("Service").style(
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Cell::from("Total Lines").style(
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Cell::from("P95 (ms)").style(
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Cell::from("P99 (ms)").style(
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
        ]);

        let mut rows: Vec<Row> = Vec::new();
        let mut sorted: Vec<_> = self.display_state.throughput_rows.iter().collect();
        sorted.sort_by(|a, b| b.1 .0.cmp(&a.1 .0));

        for (svc, (count, p95, p99)) in sorted {
            rows.push(Row::new(vec![
                Cell::from(svc.as_str()).style(Style::default().fg(Color::White)),
                Cell::from(format!("{}", count)).style(Style::default().fg(Color::Green)),
                Cell::from(format!("{:.2}", p95)).style(Style::default().fg(Color::Yellow)),
                Cell::from(format!("{:.2}", p99)).style(Style::default().fg(Color::Magenta)),
            ]));
        }

        if rows.is_empty() {
            rows.push(Row::new(vec![
                Cell::from("(no throughput data)").style(Style::default().fg(Color::DarkGray)),
                Cell::from(""),
                Cell::from(""),
                Cell::from(""),
            ]));
        }

        Table::new(rows)
            .header(header)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(" Throughput ")
                    .border_style(Style::default().fg(Color::Green)),
            )
            .widths(&[
                Constraint::Percentage(40),
                Constraint::Percentage(20),
                Constraint::Percentage(20),
                Constraint::Percentage(20),
            ])
            .column_spacing(1)
    }

    fn build_header(&self) -> Paragraph {
        let mode_text = if self.is_in_history_mode {
            format!(
                " [HISTORY MODE] Window {}/{} ",
                self.current_history_index + 1,
                self.history.len()
            )
        } else {
            " [LIVE] ".to_string()
        };

        let window_text = self
            .display_state
            .current_window
            .map(|w| format!("Window: {}", w.format("%Y-%m-%d %H:%M:%S UTC")))
            .unwrap_or_else(|| "Waiting for data...".to_string());

        let text = vec![Spans::from(vec![
            Span::styled(
                " LogForge Dashboard ",
                Style::default()
                    .fg(Color::Black)
                    .bg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("   "),
            Span::styled(
                mode_text,
                Style::default()
                    .fg(if self.is_in_history_mode {
                        Color::Yellow
                    } else {
                        Color::Green
                    })
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("   "),
            Span::styled(window_text, Style::default().fg(Color::White)),
        ])];

        Paragraph::new(text)
            .block(Block::default().borders(Borders::ALL))
            .alignment(Alignment::Left)
            .wrap(Wrap { trim: true })
    }

    fn build_footer(&self) -> Paragraph {
        let help_text = vec![Spans::from(vec![
            Span::styled(
                " ↑↓ ",
                Style::default()
                    .fg(Color::Black)
                    .bg(Color::Yellow)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw(" Browse History  "),
            Span::styled(
                " Q ",
                Style::default()
                    .fg(Color::Black)
                    .bg(Color::Red)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw(" Quit  "),
            Span::styled(
                " R ",
                Style::default()
                    .fg(Color::Black)
                    .bg(Color::Green)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw(" Return to Live "),
            Span::raw("   "),
            Span::styled(
                format!("Services: {} ", self.display_state.services.len()),
                Style::default().fg(Color::Cyan),
            ),
        ])];

        Paragraph::new(help_text)
            .block(Block::default().borders(Borders::ALL))
            .alignment(Alignment::Left)
    }

    fn draw(&self, f: &mut Frame<CrosstermBackend<std::io::Stdout>>) {
        let size = f.size();

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .margin(0)
            .constraints(
                [
                    Constraint::Length(3),
                    Constraint::Min(8),
                    Constraint::Min(8),
                    Constraint::Length(3),
                ]
                .as_ref(),
            )
            .split(size);

        f.render_widget(self.build_header(), chunks[0]);
        f.render_widget(self.build_error_table(), chunks[1]);
        f.render_widget(self.build_throughput_table(), chunks[2]);
        f.render_widget(self.build_footer(), chunks[3]);
    }

    fn update_with_events(&mut self, events: Vec<WindowStats>) {
        if self.is_in_history_mode {
            return;
        }

        if events.is_empty() {
            return;
        }

        let window_start = events[0].window_start;

        while self.history.len() >= HISTORY_WINDOW_COUNT {
            self.history.pop_front();
        }
        self.history.push_back(events.clone());
        self.current_history_index = self.history.len().saturating_sub(1);

        self.display_state.apply_window(&events);
        debug!(
            "Dashboard updated with window {} ({} stats, history len={})",
            window_start,
            events.len(),
            self.history.len()
        );
    }

    fn show_history_at(&mut self, index: usize) {
        if self.history.is_empty() {
            return;
        }

        let idx = index.min(self.history.len() - 1);
        self.current_history_index = idx;
        self.is_in_history_mode = true;

        self.display_state = DashboardState::new();
        if let Some(window) = self.history.get(idx) {
            self.display_state.apply_window(window);
        }
    }

    fn return_to_live(&mut self) {
        if !self.is_in_history_mode {
            return;
        }
        self.is_in_history_mode = false;
        self.current_history_index = self.history.len().saturating_sub(1);

        self.display_state = DashboardState::new();
        for window in &self.history {
            self.display_state.apply_window(window);
        }
    }

    fn handle_key(&mut self, key: KeyEvent) -> bool {
        match (key.code, key.modifiers) {
            (KeyCode::Char('q'), _)
            | (KeyCode::Char('Q'), _)
            | (KeyCode::Esc, _) => {
                return false;
            }
            (KeyCode::Char('r'), _) | (KeyCode::Char('R'), _) => {
                self.return_to_live();
            }
            (KeyCode::Up, _) => {
                if self.history.len() > 1 {
                    let new_index = if self.is_in_history_mode {
                        self.current_history_index.saturating_sub(1)
                    } else {
                        self.history.len().saturating_sub(2)
                    };
                    self.show_history_at(new_index);
                }
            }
            (KeyCode::Down, _) => {
                if self.is_in_history_mode
                    && self.current_history_index < self.history.len().saturating_sub(1)
                {
                    self.show_history_at(self.current_history_index + 1);
                } else if self.is_in_history_mode
                    && self.current_history_index == self.history.len().saturating_sub(1)
                {
                    self.return_to_live();
                }
            }
            (KeyCode::PageUp, _) => {
                if self.history.len() > 1 {
                    let new_index = self.current_history_index.saturating_sub(5);
                    self.show_history_at(new_index);
                }
            }
            (KeyCode::PageDown, _) => {
                if self.is_in_history_mode {
                    let new_index = (self.current_history_index + 5)
                        .min(self.history.len().saturating_sub(1));
                    self.show_history_at(new_index);
                    if self.current_history_index == self.history.len().saturating_sub(1) {
                        self.return_to_live();
                    }
                }
            }
            _ => {}
        }
        true
    }

    pub async fn run(self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        info!("Dashboard starting in event-driven mode");

        enable_raw_mode()?;
        let mut stdout = std::io::stdout();
        execute!(stdout, EnterAlternateScreen)?;
        let backend = CrosstermBackend::new(stdout);
        let mut terminal = Terminal::new(backend)?;
        terminal.clear()?;

        let this = Arc::new(tokio::sync::Mutex::new(self));

        let this_clone = this.clone();
        let event_task = tokio::spawn(async move {
            let mut this_guard = this_clone.lock().await;
            loop {
                match this_guard.event_rx.recv().await {
                    Ok(events) => {
                        this_guard.update_with_events(events);
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        warn!("Dashboard event receiver lagged by {} messages", n);
                    }
                    Err(broadcast::error::RecvError::Closed) => {
                        debug!("Dashboard event channel closed");
                        break;
                    }
                }
            }
        });

        let mut last_tick = Utc::now();
        let this_clone = this.clone();
        let main_loop = async move {
            loop {
                let draw_needed = {
                    let this_guard = this_clone.lock().await;
                    true
                };

                if draw_needed {
                    let mut this_guard = this_clone.lock().await;
                    terminal.draw(|f| this_guard.draw(f))?;
                }

                if event::poll(Duration::from_millis(REFRESH_INTERVAL_MS))? {
                    if let Event::Key(key) = event::read()? {
                        let mut this_guard = this_clone.lock().await;
                        if !this_guard.handle_key(key) {
                            break;
                        }
                    }
                }

                let now = Utc::now();
                if (now - last_tick).num_milliseconds() > 1000 {
                    last_tick = now;
                }
            }
            Ok::<(), Box<dyn std::error::Error + Send + Sync>>(())
        };

        let result = main_loop.await;

        event_task.abort();

        disable_raw_mode()?;
        execute!(
            terminal.backend_mut(),
            LeaveAlternateScreen,
        )?;
        terminal.show_cursor()?;

        info!("Dashboard stopped");
        result
    }
}
