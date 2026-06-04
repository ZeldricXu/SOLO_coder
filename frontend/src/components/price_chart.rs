use leptos::*;
use rust_decimal::prelude::ToPrimitive;
use rust_decimal::Decimal;
use uuid::Uuid;
use crate::types::PriceUpdate;
use crate::services::sse::use_sse;

#[component]
pub fn PriceChart(
    auction_id: Uuid,
    #[prop(default = 200)] width: u32,
    #[prop(default = 80)] height: u32,
) -> impl IntoView {
    let sse = use_sse();
    let price_signal = sse.get_price(auction_id);
    let padding = 10;
    let chart_width = width - padding * 2;
    let chart_height = height - padding * 2;

    let chart_data = Signal::derive(move || {
        let update = price_signal.get();
        if let Some(update) = update {
            if !update.price_history.is_empty() || !update.price_forecast.is_empty() {
                return Some(generate_chart_data(
                    &update,
                    chart_width as f64,
                    chart_height as f64,
                    padding as f64,
                ));
            }
        }
        None
    });

    view! {
        <svg width=width height=height class="price-chart">
            <defs>
                <linearGradient id="historyGradient" x1="0%" y1="0%" x2="0%" y2="100%">
                    <stop offset="0%" stop-color="#ef4444" stop-opacity="0.3"/>
                    <stop offset="100%" stop-color="#ef4444" stop-opacity="0"/>
                </linearGradient>
                <linearGradient id="forecastGradient" x1="0%" y1="0%" x2="0%" y2="100%">
                    <stop offset="0%" stop-color="#3b82f6" stop-opacity="0.2"/>
                    <stop offset="100%" stop-color="#3b82f6" stop-opacity="0"/>
                </linearGradient>
            </defs>

            <line
                x1=padding y1=height/2
                x2=width-padding y2=height/2
                stroke="#e5e7eb" stroke-width="1" stroke-dasharray="4,4"
            />

            {move || chart_data.get().map(|data| view! {
                <path
                    d=data.history_area
                    fill="url(#historyGradient)"
                />
                <path
                    d=data.history_line
                    fill="none"
                    stroke="#ef4444"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                />
                {if !data.forecast_line.is_empty() {
                    view! {
                        <path
                            d=data.forecast_area
                            fill="url(#forecastGradient)"
                        />
                        <path
                            d=data.forecast_line
                            fill="none"
                            stroke="#3b82f6"
                            stroke-width="2"
                            stroke-dasharray="5,3"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                        />
                        <circle
                            cx=data.current_x
                            cy=data.current_y
                            r="4"
                            fill="#ef4444"
                            stroke="white"
                            stroke-width="2"
                        />
                    }.into_view()
                } else {
                    ().into_view()
                }}

                <text
                    x=padding y=padding+12
                    class="text-xs fill-gray-500 font-medium"
                >
                    {format!("¥{:.1}", data.max_price)}
                </text>
                <text
                    x=padding y=height-padding-2
                    class="text-xs fill-gray-500 font-medium"
                >
                    {format!("¥{:.1}", data.min_price)}
                </text>
            })}
        </svg>
    }
}

struct ChartData {
    history_line: String,
    history_area: String,
    forecast_line: String,
    forecast_area: String,
    current_x: f64,
    current_y: f64,
    max_price: f64,
    min_price: f64,
}

fn generate_chart_data(
    update: &PriceUpdate,
    width: f64,
    height: f64,
    padding: f64,
) -> ChartData {
    let all_points: Vec<(f64, f64)> = update.price_history
        .iter()
        .chain(update.price_forecast.iter())
        .map(|p| (
            p.timestamp.timestamp_millis() as f64,
            p.price.to_f64().unwrap_or(0.0),
        ))
        .collect();

    if all_points.is_empty() {
        return ChartData {
            history_line: String::new(),
            history_area: String::new(),
            forecast_line: String::new(),
            forecast_area: String::new(),
            current_x: 0.0,
            current_y: 0.0,
            max_price: 0.0,
            min_price: 0.0,
        };
    }

    let min_time = all_points.first().map(|(t, _)| *t).unwrap_or(0.0);
    let max_time = all_points.last().map(|(t, _)| *t).unwrap_or(0.0);
    let time_range = if max_time > min_time { max_time - min_time } else { 1.0 };

    let max_price = all_points.iter().map(|(_, p)| *p).fold(f64::NEG_INFINITY, f64::max);
    let min_price = all_points.iter().map(|(_, p)| *p).fold(f64::INFINITY, f64::min);
    let price_range = if max_price > min_price { max_price - min_price } else { 1.0 };

    let price_margin = price_range * 0.1;
    let max_display = max_price + price_margin;
    let min_display = min_price - price_margin;
    let display_range = max_display - min_display;

    let to_x = |t: f64| -> f64 {
        padding + ((t - min_time) / time_range) * width
    };

    let to_y = |p: f64| -> f64 {
        padding + height - ((p - min_display) / display_range) * height
    };

    let history_points: Vec<(f64, f64)> = update.price_history
        .iter()
        .map(|p| (
            to_x(p.timestamp.timestamp_millis() as f64),
            to_y(p.price.to_f64().unwrap_or(0.0)),
        ))
        .collect();

    let forecast_points: Vec<(f64, f64)> = update.price_forecast
        .iter()
        .map(|p| (
            to_x(p.timestamp.timestamp_millis() as f64),
            to_y(p.price.to_f64().unwrap_or(0.0)),
        ))
        .collect();

    let history_line = if !history_points.is_empty() {
        generate_smooth_path(&history_points)
    } else {
        String::new()
    };

    let history_area = if history_points.len() >= 2 {
        let first = history_points.first().unwrap();
        let last = history_points.last().unwrap();
        format!(
            "{} L {} {} L {} {} Z",
            history_line,
            last.0, padding + height,
            first.0, padding + height
        )
    } else {
        String::new()
    };

    let forecast_line = if !forecast_points.is_empty() {
        let mut points = Vec::new();
        if let Some(last_hist) = history_points.last() {
            points.push(*last_hist);
        }
        points.extend(forecast_points.iter().cloned());
        generate_smooth_path(&points)
    } else {
        String::new()
    };

    let forecast_area = if !forecast_points.is_empty() && history_points.len() >= 1 {
        let first = history_points.last().unwrap_or(&(0.0, 0.0));
        let last = forecast_points.last().unwrap_or(&(0.0, 0.0));
        format!(
            "{} L {} {} L {} {} Z",
            forecast_line,
            last.0, padding + height,
            first.0, padding + height
        )
    } else {
        String::new()
    };

    let (current_x, current_y) = history_points.last().copied().unwrap_or((0.0, 0.0));

    ChartData {
        history_line,
        history_area,
        forecast_line,
        forecast_area,
        current_x,
        current_y,
        max_price,
        min_price,
    }
}

fn generate_smooth_path(points: &[(f64, f64)]) -> String {
    if points.is_empty() {
        return String::new();
    }
    if points.len() == 1 {
        return format!("M {} {}", points[0].0, points[0].1);
    }

    let mut path = format!("M {} {}", points[0].0, points[0].1);

    for i in 0..points.len() - 1 {
        let (x0, y0) = if i > 0 { points[i - 1] } else { points[i] };
        let (x1, y1) = points[i];
        let (x2, y2) = points[i + 1];
        let (x3, y3) = if i + 2 < points.len() { points[i + 2] } else { points[i + 1] };

        let cp1x = x1 + (x2 - x0) / 6.0;
        let cp1y = y1 + (y2 - y0) / 6.0;
        let cp2x = x2 - (x3 - x1) / 6.0;
        let cp2y = y2 - (y3 - y1) / 6.0;

        path.push_str(&format!(" C {} {}, {} {}, {} {}", cp1x, cp1y, cp2x, cp2y, x2, y2));
    }

    path
}
