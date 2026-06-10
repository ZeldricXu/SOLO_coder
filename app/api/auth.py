from flask import Blueprint, request, jsonify, render_template, redirect, url_for, flash
from flask_login import login_user, logout_user, login_required, current_user
from app.services.auth_service import register_user, authenticate_user, update_user_profile, change_password
from app.utils.decorators import login_required_api, validate_json

auth_bp = Blueprint('auth', __name__)


@auth_bp.route('/login', methods=['GET'])
def login_page():
    if current_user.is_authenticated:
        return redirect(url_for('dashboard_list'))
    return render_template('auth/login.html')


@auth_bp.route('/login', methods=['POST'])
def login():
    if request.is_json:
        data = request.get_json()
        email = data.get('email')
        password = data.get('password')
    else:
        email = request.form.get('email')
        password = request.form.get('password')

    if not email or not password:
        if request.is_json:
            return jsonify({'success': False, 'message': '请输入邮箱和密码'}), 400
        flash('请输入邮箱和密码', 'error')
        return render_template('auth/login.html')

    user = authenticate_user(email, password)
    if not user:
        if request.is_json:
            return jsonify({'success': False, 'message': '邮箱或密码错误'}), 401
        flash('邮箱或密码错误', 'error')
        return render_template('auth/login.html')

    login_user(user, remember=True)

    if request.is_json:
        return jsonify({
            'success': True,
            'message': '登录成功',
            'user': {
                'id': user.id,
                'email': user.email,
                'name': user.name,
                'avatar': user.avatar,
                'role': user.role.name if user.role else None
            }
        })

    next_url = request.args.get('next') or url_for('dashboard_list')
    return redirect(next_url)


@auth_bp.route('/register', methods=['GET'])
def register_page():
    if current_user.is_authenticated:
        return redirect(url_for('dashboard_list'))
    return render_template('auth/register.html')


@auth_bp.route('/register', methods=['POST'])
def register():
    if request.is_json:
        data = request.get_json()
        email = data.get('email')
        password = data.get('password')
        name = data.get('name')
    else:
        email = request.form.get('email')
        password = request.form.get('password')
        name = request.form.get('name')

    if not email or not password or not name:
        if request.is_json:
            return jsonify({'success': False, 'message': '请填写完整信息'}), 400
        flash('请填写完整信息', 'error')
        return render_template('auth/register.html')

    try:
        user = register_user(email, password, name)
        login_user(user, remember=True)

        if request.is_json:
            return jsonify({
                'success': True,
                'message': '注册成功',
                'user': {
                    'id': user.id,
                    'email': user.email,
                    'name': user.name,
                    'avatar': user.avatar
                }
            })

        return redirect(url_for('dashboard_list'))
    except ValueError as e:
        if request.is_json:
            return jsonify({'success': False, 'message': str(e)}), 400
        flash(str(e), 'error')
        return render_template('auth/register.html')


@auth_bp.route('/logout')
@login_required
def logout():
    logout_user()
    if request.is_json or request.accept_mimetypes.accept_json:
        return jsonify({'success': True, 'message': '已退出登录'})
    return redirect(url_for('index'))


@auth_bp.route('/api/me', methods=['GET'])
@login_required_api
def get_current_user():
    return jsonify({
        'success': True,
        'data': {
            'id': current_user.id,
            'email': current_user.email,
            'name': current_user.name,
            'avatar': current_user.avatar,
            'role': current_user.role.name if current_user.role else None,
            'created_at': current_user.created_at.isoformat() if current_user.created_at else None
        }
    })


@auth_bp.route('/api/profile', methods=['PUT'])
@login_required_api
@validate_json()
def update_profile():
    data = request.get_json()
    try:
        user = update_user_profile(current_user.id, **data)
        return jsonify({
            'success': True,
            'message': '更新成功',
            'data': {
                'id': user.id,
                'name': user.name,
                'avatar': user.avatar
            }
        })
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@auth_bp.route('/api/password', methods=['PUT'])
@login_required_api
@validate_json('old_password', 'new_password')
def update_password():
    data = request.get_json()
    try:
        change_password(
            current_user.id,
            data['old_password'],
            data['new_password']
        )
        return jsonify({'success': True, 'message': '密码修改成功'})
    except ValueError as e:
        return jsonify({'success': False, 'message': str(e)}), 400
