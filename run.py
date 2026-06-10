import os
import sys
from app import create_app, db, celery
from app.models import User, Team, Dashboard, DataSource, Chart, Template

app = create_app(os.getenv('FLASK_ENV', 'default'))


@app.shell_context_processor
def make_shell_context():
    return {
        'db': db,
        'User': User,
        'Team': Team,
        'Dashboard': Dashboard,
        'DataSource': DataSource,
        'Chart': Chart,
        'Template': Template,
    }


@app.cli.command('init-db')
def init_db():
    from app.services.init_service import init_database
    init_database()
    print('Database initialized successfully.')


@app.cli.command('create-admin')
def create_admin():
    from app.services.auth_service import create_admin_user
    import getpass
    email = input('Admin email: ')
    password = getpass.getpass('Admin password: ')
    name = input('Admin name: ')
    user = create_admin_user(email, password, name)
    print(f'Admin user created: {user.email}')


@app.cli.command('seed-templates')
def seed_templates():
    from app.services.template_service import seed_default_templates
    seed_default_templates()
    print('Default templates seeded successfully.')


def main():
    host = os.getenv('HOST', '0.0.0.0')
    port = int(os.getenv('PORT', 5000))
    debug = app.config['DEBUG']
    app.run(host=host, port=port, debug=debug)


if __name__ == '__main__':
    main()
