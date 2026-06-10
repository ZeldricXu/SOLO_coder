import os
import json
import time
import smtplib
import logging
from datetime import datetime
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.mime.base import MIMEBase
from email import encoders
from io import BytesIO
from flask import current_app, url_for, render_template_string
from app import celery, db
from app.models import Report, ReportSchedule
from app.services.chart_service import get_dashboard_charts, get_chart_data

logger = logging.getLogger(__name__)


@celery.task(bind=True)
def generate_report_task(self, report_id):
    report = Report.query.get(report_id)
    if not report:
        return {'error': 'Report not found'}

    try:
        report.status = 'generating'
        db.session.add(report)
        db.session.commit()

        dashboard = report.dashboard
        if not dashboard:
            raise ValueError('Dashboard not found')

        from app import create_app
        app = create_app()
        with app.app_context():
            file_path = None
            snapshot_url = None
            data_summary = {}

            if report.include_snapshot:
                snapshot_url = capture_dashboard_snapshot(dashboard.id)
                report.snapshot_url = snapshot_url

            if report.include_data:
                data_summary = collect_dashboard_data(dashboard.id)
                report.set_data_summary(data_summary)

            if report.file_type == 'pdf':
                file_path = generate_pdf_report(dashboard, report, snapshot_url, data_summary)
            elif report.file_type == 'png':
                file_path = snapshot_url if snapshot_url else capture_dashboard_snapshot(dashboard.id)
            elif report.file_type == 'excel':
                file_path = generate_excel_report(dashboard, data_summary)

            if file_path and os.path.exists(file_path):
                report.file_path = file_path
                report.file_size = os.path.getsize(file_path)

            if report.schedule_id:
                schedule = ReportSchedule.query.get(report.schedule_id)
                if schedule and schedule.get_recipients():
                    send_report_email(report, schedule.get_recipients())
                    report.set_sent_to(schedule.get_recipients())
                    report.status = 'sent'
                else:
                    report.status = 'completed'
            else:
                report.status = 'completed'

            report.completed_at = datetime.utcnow()
            db.session.add(report)
            db.session.commit()

            return {'success': True, 'report_id': report_id, 'status': report.status}

    except Exception as e:
        logger.error(f"Report generation failed: {str(e)}", exc_info=True)
        report.status = 'failed'
        report.error_message = str(e)
        db.session.add(report)
        db.session.commit()
        return {'success': False, 'error': str(e)}


def capture_dashboard_snapshot(dashboard_id):
    from flask import current_app
    try:
        from playwright.sync_api import sync_playwright

        snapshot_dir = current_app.config['SNAPSHOT_FOLDER']
        os.makedirs(snapshot_dir, exist_ok=True)

        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        filename = f'dashboard_{dashboard_id}_{timestamp}.png'
        file_path = os.path.join(snapshot_dir, filename)

        view_url = f"{current_app.config.get('BASE_URL', 'http://localhost:5000')}/dashboards/{dashboard_id}"

        with sync_playwright() as p:
            browser = p.chromium.launch(
                executable_path=current_app.config.get('PLAYWRIGHT_EXECUTABLE_PATH'),
                headless=True
            )
            context = browser.new_context(
                viewport={'width': 1920, 'height': 1080},
                device_scale_factor=2
            )
            page = context.new_page()
            page.goto(view_url, wait_until='networkidle', timeout=60000)
            time.sleep(2)
            page.screenshot(path=file_path, full_page=True)
            browser.close()

        return file_path
    except Exception as e:
        logger.error(f"Snapshot capture failed: {str(e)}", exc_info=True)
        return None


def collect_dashboard_data(dashboard_id):
    charts = get_dashboard_charts(dashboard_id)
    summary = {
        'generated_at': datetime.now().isoformat(),
        'chart_count': len(charts),
        'charts': []
    }

    for chart in charts:
        try:
            data = get_chart_data(chart.id)
            chart_summary = {
                'chart_id': chart.id,
                'chart_name': chart.name,
                'chart_type': chart.chart_type,
            }
            if data.get('success') and 'data' in data:
                chart_data = data['data']
                if 'values' in chart_data and chart_data['values']:
                    values = chart_data['values']
                    chart_summary.update({
                        'min': min(values) if values else None,
                        'max': max(values) if values else None,
                        'avg': sum(values) / len(values) if values else None,
                        'sum': sum(values) if values else None,
                        'count': len(values)
                    })
            summary['charts'].append(chart_summary)
        except Exception as e:
            logger.warning(f"Failed to collect data for chart {chart.id}: {str(e)}")

    return summary


def generate_pdf_report(dashboard, report, snapshot_url, data_summary):
    from flask import current_app, render_template
    try:
        from weasyprint import HTML, CSS

        export_dir = current_app.config['EXPORT_FOLDER']
        os.makedirs(export_dir, exist_ok=True)

        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        filename = f'report_{dashboard.id}_{timestamp}.pdf'
        file_path = os.path.join(export_dir, filename)

        html_content = render_template('report/pdf_template.html',
                                       dashboard=dashboard,
                                       report=report,
                                       snapshot_url=snapshot_url,
                                       data_summary=data_summary,
                                       generated_at=datetime.now())

        HTML(string=html_content).write_pdf(
            file_path,
            stylesheets=[CSS(string='@page { size: A4; margin: 1cm; }')]
        )

        return file_path
    except Exception as e:
        logger.error(f"PDF generation failed: {str(e)}", exc_info=True)
        return None


def generate_excel_report(dashboard, data_summary):
    from flask import current_app
    try:
        import pandas as pd

        export_dir = current_app.config['EXPORT_FOLDER']
        os.makedirs(export_dir, exist_ok=True)

        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        filename = f'report_{dashboard.id}_{timestamp}.xlsx'
        file_path = os.path.join(export_dir, filename)

        with pd.ExcelWriter(file_path, engine='openpyxl') as writer:
            summary_data = []
            for chart in data_summary.get('charts', []):
                summary_data.append({
                    '图表名称': chart.get('chart_name'),
                    '图表类型': chart.get('chart_type'),
                    '最小值': chart.get('min'),
                    '最大值': chart.get('max'),
                    '平均值': chart.get('avg'),
                    '总和': chart.get('sum'),
                    '数据点数': chart.get('count')
                })

            if summary_data:
                df_summary = pd.DataFrame(summary_data)
                df_summary.to_excel(writer, sheet_name='数据摘要', index=False)

        return file_path
    except Exception as e:
        logger.error(f"Excel generation failed: {str(e)}", exc_info=True)
        return None


def send_report_email(report, recipients):
    from flask import current_app
    try:
        mail_server = current_app.config.get('MAIL_SERVER')
        mail_port = current_app.config.get('MAIL_PORT')
        mail_username = current_app.config.get('MAIL_USERNAME')
        mail_password = current_app.config.get('MAIL_PASSWORD')
        mail_use_tls = current_app.config.get('MAIL_USE_TLS', True)
        mail_sender = current_app.config.get('MAIL_DEFAULT_SENDER', mail_username)

        if not all([mail_server, mail_port, mail_username, mail_password]):
            logger.warning("Email configuration incomplete, skipping email sending")
            return False

        msg = MIMEMultipart()
        msg['From'] = mail_sender
        msg['To'] = ', '.join(recipients)
        msg['Subject'] = f'[报表] {report.title}'

        body = f'''
        <html>
        <body>
            <h2>{report.title}</h2>
            <p>您好，</p>
            <p>这是自动生成的报表，请查收附件。</p>
            <p>生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
            <br/>
            <p>此邮件由系统自动发送，请勿直接回复。</p>
        </body>
        </html>
        '''

        msg.attach(MIMEText(body, 'html', 'utf-8'))

        if report.file_path and os.path.exists(report.file_path):
            with open(report.file_path, 'rb') as f:
                part = MIMEBase('application', 'octet-stream')
                part.set_payload(f.read())
                encoders.encode_base64(part)
                part.add_header(
                    'Content-Disposition',
                    f'attachment; filename="{os.path.basename(report.file_path)}"'
                )
                msg.attach(part)

        with smtplib.SMTP(mail_server, mail_port) as server:
            if mail_use_tls:
                server.starttls()
            server.login(mail_username, mail_password)
            server.sendmail(mail_sender, recipients, msg.as_string())

        logger.info(f"Report email sent to {recipients}")
        return True

    except Exception as e:
        logger.error(f"Email sending failed: {str(e)}", exc_info=True)
        return False


@celery.task
def process_scheduled_reports():
    now = datetime.utcnow()
    schedules = ReportSchedule.query.filter(
        ReportSchedule.is_active == True,
        ReportSchedule.next_run_at <= now
    ).all()

    for schedule in schedules:
        try:
            trigger_report_schedule(schedule.id)
            logger.info(f"Scheduled report triggered: {schedule.id}")
        except Exception as e:
            logger.error(f"Failed to trigger schedule {schedule.id}: {str(e)}")

    return {'processed': len(schedules)}
