use crate::aggregator::AggregationEngine;
use chrono::Local;
use crossterm::{
    event::{self, DisableMouseCapture, EnableMouseCapture, Event, KeyCode, KeyEvent},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use std::io;
use std::sync::Arc;
use std::time::Duration;
use tui::{
    backend::{Backend, CrosstermBackend},
    layout::{Alignment, Constraint, Direction, Layout, Rect},
    style::{Color, Modifier, Style},
    text::{Span, Spans},
    widgets::{Block, Borders, Cell, Paragraph, Row, Table, Wrap},
    Frame, Terminal,
};
use tracing::{debug, info, warn};

struct AppState {
    running: bool,
    tick_count: u64,
}

impl AppState {
    fn new() -> Self {
        Self {
            running: true,
            tick_count: 0,
        }
    }
}

pub struct Dashboard {
    aggregator: Arc<AggregationEngine>,
}

impl Dashboard {
    pub fn new(aggregator: Arc<AggregationEngine>) -> Self {
        Self { aggregator }
    }

    pub async fn run(self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        tokio::task::spawn_blocking(move || {
            if let Err(e) = run_tui(self.aggregator) {
                warn!("TUI dashboard exited with error: {}", e);
            }
        })
        .await
        .ok();
        Ok(())
    }
}

fn run_tui(agg: Arc<AggregationEngine>) -> Result<(), Box<dyn std::error::Error>> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen, EnableMouseCapture)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;

    let state = Arc::new(parking_lot::Mutex::new(AppState::new()));

    let result = run_app(&mut terminal, agg, state.clone());

    disable_raw_mode()?;
    execute!(
        terminal.backend_mut(),
        LeaveAlternateScreen,
        DisableMouseCapture
    )?;
    terminal.show_cursor()?;

    if let Err(err) = result {
        eprintln!("{:?}", err);
    }

    Ok(())
}

fn run_app<B: Backend>(
    terminal: &mut Terminal<B>,
    agg: Arc<AggregationEngine>,
    state: Arc<parking_lot::Mutex<AppState>>,
) -> io::Result<()> {
    loop {
        {
            let s = state.lock();
            if !s.running {
                break;
            }
        }
        terminal.draw(|f| ui(f, &agg))?;

        if event::poll(Duration::from_millis(500))? {
            if let Event::Key(key) = event::read()? {
                handle_key(key, &state);
            }
        }
    }
    Ok(())
}

fn handle_key(key: KeyEvent, state: &Arc<parking_lot::Mutex<AppState>>) {
    match key.code {
        KeyCode::Char('q') | KeyCode::Esc | KeyCode::Char('Q') => {
            let mut s = state.lock();
            s.running = false;
        }
        _ => {}
    }
}

fn ui<B: Backend>(f: &mut Frame<B>, agg: &Arc<AggregationEngine>) {
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .margin(1)
        .constraints(
            [
                Constraint::Length(3),
                Constraint::Min(6),
                Constraint::Min(6),
                Constraint::Length(3),
            ]
            .as_ref(),
        )
        .split(f.size());

    render_header(f, chunks[0]);
    render_error_table(f, chunks[1], agg);
    render_service_table(f, chunks[2], agg);
    render_footer(f, chunks[3]);
}

fn render_header<B: Backend>(f: &mut Frame<B>, area: Rect) {
    let now = Local::now();
    let header = Paragraph::new(vec![Spans::from(vec![
        Span::styled(
            " ██████╗  ██████╗  ██████╗ ███████╗ ██████╗ ██████╗  ██████╗ ███████╗",
            Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD),
        ),
    ]), Spans::from(vec![
        Span::styled(
            "██╔══██╗██╔═══██╗██╔════╝ ██╔════╝██╔════╝ ██╔══██╗██╔═══██╗██╔════╝",
            Style::default().fg(Color::Cyan),
        ),
    ]), Spans::from(vec![
        Span::raw("                    "),
        Span::styled("High-Performance Log Aggregation Engine", Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)),
        Span::raw("    "),
        Span::styled(format!("{}", now.format("%Y-%m-%d %H:%M:%S")), Style::default().fg(Color::Green)),
    ])])
    .block(Block::default().borders(Borders::ALL).title(" LogForge Dashboard "))
    .alignment(Alignment::Center)
    .wrap(Wrap { trim: false });
    f.render_widget(header, area);
}

fn render_error_table<B: Backend>(f: &mut Frame<B>, area: Rect, agg: &Arc<AggregationEngine>) {
    let snapshot = agg.error_stats_snapshot();
    let header_cells = ["Service", "Errors (1m)", "P99 Latency (ms)"]
        .iter()
        .map(|h| Cell::from(*h).style(Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)));
    let header = Row::new(header_cells)
        .style(Style::default().bg(Color::Black))
        .height(1)
        .bottom_margin(1);

    let rows: Vec<Row> = snapshot
        .iter()
        .map(|(svc, cnt, p99)| {
            let (cnt_style, p99_style) = if *cnt >= 50 {
                (Style::default().fg(Color::Red).add_modifier(Modifier::BOLD),
                 Style::default().fg(Color::Red).add_modifier(Modifier::BOLD))
            } else if *cnt >= 10 {
                (Style::default().fg(Color::Yellow),
                 Style::default().fg(Color::Yellow))
            } else {
                (Style::default().fg(Color::White),
                 Style::default().fg(Color::White))
            };
            Row::new(vec![
                Cell::from(svc.as_str()),
                Cell::from(format!("{}", cnt)).style(cnt_style),
                Cell::from(format!("{:.1}", p99)).style(p99_style),
            ]).height(1)
        })
        .collect();

    let t = Table::new(rows)
        .header(header)
        .block(
            Block::default()
                .borders(Borders::ALL)
                .title(" Error Rate by Service (last 1 minute) ")
                .title_style(Style::default().fg(Color::Red).add_modifier(Modifier::BOLD)),
        )
        .widths(&[
            Constraint::Percentage(40),
            Constraint::Percentage(30),
            Constraint::Percentage(30),
        ])
        .column_spacing(2);
    f.render_widget(t, area);
}

fn render_service_table<B: Backend>(f: &mut Frame<B>, area: Rect, agg: &Arc<AggregationEngine>) {
    let snapshot = agg.services_snapshot();
    let header_cells = ["Service", "Total Lines (1m)", "P95 (ms)", "P99 (ms)"]
        .iter()
        .map(|h| Cell::from(*h).style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD)));
    let header = Row::new(header_cells)
        .style(Style::default().bg(Color::Black))
        .height(1)
        .bottom_margin(1);

    let rows: Vec<Row> = snapshot
        .iter()
        .map(|(svc, cnt, p95, p99)| {
            let p99_style = if *p99 >= 1000.0 {
                Style::default().fg(Color::Red).add_modifier(Modifier::BOLD)
            } else if *p99 >= 500.0 {
                Style::default().fg(Color::Yellow)
            } else {
                Style::default().fg(Color::Green)
            };
            Row::new(vec![
                Cell::from(svc.as_str()).style(Style::default().fg(Color::Blue).add_modifier(Modifier::BOLD)),
                Cell::from(format!("{}", cnt)),
                Cell::from(format!("{:.1}", p95)),
                Cell::from(format!("{:.1}", p99)).style(p99_style),
            ]).height(1)
        })
        .collect();

    let t = Table::new(rows)
        .header(header)
        .block(
            Block::default()
                .borders(Borders::ALL)
                .title(" Service Throughput & Latency (last 1 minute) ")
                .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD)),
        )
        .widths(&[
            Constraint::Percentage(30),
            Constraint::Percentage(25),
            Constraint::Percentage(22),
            Constraint::Percentage(22),
        ])
        .column_spacing(2);
    f.render_widget(t, area);
}

fn render_footer<B: Backend>(f: &mut Frame<B>, area: Rect) {
    let footer = Paragraph::new(Spans::from(vec![
        Span::styled(" [Q/Esc] ", Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)),
        Span::raw("Quit    "),
        Span::styled(" Aggregation ", Style::default().fg(Color::Green).add_modifier(Modifier::BOLD)),
        Span::raw("=10s fine + 5m coarse    "),
        Span::styled(" t-Digest ", Style::default().fg(Color::Green).add_modifier(Modifier::BOLD)),
        Span::raw("compression=100    "),
        Span::styled(" Target ", Style::default().fg(Color::Magenta).add_modifier(Modifier::BOLD)),
        Span::raw(">80% log reduction"),
    ]))
    .block(Block::default().borders(Borders::ALL))
    .alignment(Alignment::Left);
    f.render_widget(footer, area);
}
