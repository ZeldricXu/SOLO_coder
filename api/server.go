package api

import (
	"accessguard/audit"
	"accessguard/auth"
	"accessguard/config"
	"accessguard/models"
	"accessguard/permission"
	"accessguard/resource"
	"accessguard/role"
	"accessguard/user"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type Server struct {
	cfg               *config.Config
	authService       *auth.Service
	permissionService *permission.Service
	auditService      audit.Service
	userService       *user.Service
	roleService       *role.Service
	resourceService   *resource.Service
	router            *http.ServeMux
}

func NewServer(cfg *config.Config, authService *auth.Service, permissionService *permission.Service, auditService audit.Service, userService *user.Service, roleService *role.Service, resourceService *resource.Service) *Server {
	s := &Server{
		cfg:               cfg,
		authService:       authService,
		permissionService: permissionService,
		auditService:      auditService,
		userService:       userService,
		roleService:       roleService,
		resourceService:   resourceService,
		router:            http.NewServeMux(),
	}

	s.registerRoutes()
	return s
}

func (s *Server) registerRoutes() {
	s.router.HandleFunc("/api/v1/auth/login", s.handleLogin)
	s.router.HandleFunc("/api/v1/auth/logout", s.handleLogout)
	s.router.HandleFunc("/api/v1/auth/check", s.handleCheckPermission)
	s.router.HandleFunc("/api/v1/auth/audit", s.handleAuditQuery)

	s.router.HandleFunc("/api/v1/users", s.handleUsers)
	s.router.HandleFunc("/api/v1/users/", s.handleUserByID)

	s.router.HandleFunc("/api/v1/roles", s.handleRoles)
	s.router.HandleFunc("/api/v1/roles/", s.handleRoleByID)

	s.router.HandleFunc("/api/v1/resources", s.handleResources)
	s.router.HandleFunc("/api/v1/resources/", s.handleResourceByID)

	s.router.HandleFunc("/api/v1/user-roles", s.handleUserRoles)
}

func (s *Server) Start() error {
	addr := fmt.Sprintf("%s:%d", s.cfg.Server.Address, s.cfg.Server.Port)
	fmt.Printf("AccessGuard server starting on %s...\n", addr)
	return http.ListenAndServe(addr, s.router)
}

type SessionNotify interface {
	HandleWebSocket(w http.ResponseWriter, r *http.Request)
}

func RegisterRoutesWithNotify(mux *http.ServeMux, server *Server, sessionNotify SessionNotify) {
	server.registerRoutesTo(mux)
	mux.HandleFunc("/api/v1/ws/session", sessionNotify.HandleWebSocket)
}

func (s *Server) registerRoutesTo(mux *http.ServeMux) {
	mux.HandleFunc("/api/v1/auth/login", s.handleLogin)
	mux.HandleFunc("/api/v1/auth/logout", s.handleLogout)
	mux.HandleFunc("/api/v1/auth/check", s.handleCheckPermission)
	mux.HandleFunc("/api/v1/auth/audit", s.handleAuditQuery)

	mux.HandleFunc("/api/v1/users", s.handleUsers)
	mux.HandleFunc("/api/v1/users/", s.handleUserByID)

	mux.HandleFunc("/api/v1/roles", s.handleRoles)
	mux.HandleFunc("/api/v1/roles/", s.handleRoleByID)

	mux.HandleFunc("/api/v1/resources", s.handleResources)
	mux.HandleFunc("/api/v1/resources/", s.handleResourceByID)

	mux.HandleFunc("/api/v1/user-roles", s.handleUserRoles)
}

func (s *Server) getClientIP(r *http.Request) string {
	ip := r.Header.Get("X-Forwarded-For")
	if ip != "" {
		parts := strings.Split(ip, ",")
		if len(parts) > 0 {
			return strings.TrimSpace(parts[0])
		}
	}

	ip = r.Header.Get("X-Real-IP")
	if ip != "" {
		return ip
	}

	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}

	return r.RemoteAddr
}

func (s *Server) writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if data != nil {
		json.NewEncoder(w).Encode(data)
	}
}

func (s *Server) writeSuccess(w http.ResponseWriter, data interface{}) {
	s.writeJSON(w, http.StatusOK, models.APIResponse{
		Code: 200,
		Data: data,
	})
}

func (s *Server) writeError(w http.ResponseWriter, code int, message string) {
	s.writeJSON(w, code, models.APIResponse{
		Code:    code,
		Message: message,
	})
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req models.LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	ip := s.getClientIP(r)
	resp, err := s.authService.Login(&req, ip)
	if err != nil {
		code := http.StatusInternalServerError
		switch err {
		case models.ErrUserNotFound:
			code = http.StatusNotFound
		case models.ErrUserDisabled:
			code = http.StatusForbidden
		case models.ErrInvalidPassword:
			code = http.StatusUnauthorized
		case models.ErrMFARequired:
			code = http.StatusUnauthorized
		case models.ErrMFAFailed:
			code = http.StatusUnauthorized
		case models.ErrInvalidRequest:
			code = http.StatusBadRequest
		}
		s.writeError(w, code, err.Error())
		return
	}

	s.writeSuccess(w, resp)
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	sessionID := r.Header.Get("X-Session-ID")
	if sessionID == "" {
		s.writeError(w, http.StatusBadRequest, "session id required")
		return
	}

	ip := s.getClientIP(r)
	err := s.authService.Logout(sessionID, ip)
	if err != nil {
		s.writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	s.writeSuccess(w, map[string]string{"status": "logged_out"})
}

func (s *Server) handleCheckPermission(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req models.PermissionCheckRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	ip := s.getClientIP(r)
	resp, err := s.permissionService.CheckPermission(&req, ip)
	if err != nil && resp == nil {
		code := http.StatusInternalServerError
		switch err {
		case models.ErrSessionNotFound:
			code = http.StatusNotFound
		case models.ErrSessionExpired:
			code = http.StatusUnauthorized
		case models.ErrSessionRevoked:
			code = http.StatusUnauthorized
		case models.ErrResourceNotFound:
			code = http.StatusNotFound
		case models.ErrInvalidRequest:
			code = http.StatusBadRequest
		}
		s.writeError(w, code, err.Error())
		return
	}

	s.writeSuccess(w, resp)
}

func (s *Server) handleAuditQuery(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	userID := r.URL.Query().Get("user_id")
	startTimeStr := r.URL.Query().Get("start_time")
	endTimeStr := r.URL.Query().Get("end_time")
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")

	var startTime, endTime time.Time
	if startTimeStr != "" {
		t, err := time.Parse(time.RFC3339, startTimeStr)
		if err == nil {
			startTime = t
		}
	}
	if endTimeStr != "" {
		t, err := time.Parse(time.RFC3339, endTimeStr)
		if err == nil {
			endTime = t
		}
	}

	limit := 100
	if limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil {
			limit = l
		}
	}

	offset := 0
	if offsetStr != "" {
		if o, err := strconv.Atoi(offsetStr); err == nil {
			offset = o
		}
	}

	records, total, err := s.auditService.QueryRecords(userID, startTime, endTime, limit, offset)
	if err != nil {
		s.writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	s.writeSuccess(w, models.AuditQueryResponse{
		AuditRecords: records,
		Total:        total,
	})
}

func (s *Server) handleUsers(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		users := s.userService.ListUsers()
		s.writeSuccess(w, users)

	case http.MethodPost:
		var req models.CreateUserRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			s.writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}

		user, err := s.userService.CreateUser(&req)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrUserAlreadyExists {
				code = http.StatusConflict
			} else if err == models.ErrInvalidRequest {
				code = http.StatusBadRequest
			}
			s.writeError(w, code, err.Error())
			return
		}

		s.writeJSON(w, http.StatusCreated, models.APIResponse{
			Code: 201,
			Data: user,
		})

	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleUserByID(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/api/v1/users/")
	if id == "" {
		s.writeError(w, http.StatusBadRequest, "user id required")
		return
	}

	switch r.Method {
	case http.MethodGet:
		user, err := s.userService.GetUserByID(id)
		if err != nil {
			s.writeError(w, http.StatusNotFound, err.Error())
			return
		}
		s.writeSuccess(w, user)

	case http.MethodPut, http.MethodPatch:
		var req models.UpdateUserRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			s.writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}

		user, err := s.userService.UpdateUser(id, &req)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrUserNotFound {
				code = http.StatusNotFound
			}
			s.writeError(w, code, err.Error())
			return
		}
		s.writeSuccess(w, user)

	case http.MethodDelete:
		err := s.userService.DeleteUser(id)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrUserNotFound {
				code = http.StatusNotFound
			}
			s.writeError(w, code, err.Error())
			return
		}
		s.writeSuccess(w, map[string]string{"status": "deleted"})

	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleRoles(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		roles := s.roleService.ListRoles()
		s.writeSuccess(w, roles)

	case http.MethodPost:
		var req models.CreateRoleRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			s.writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}

		role, err := s.roleService.CreateRole(&req)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrRoleAlreadyExists {
				code = http.StatusConflict
			} else if err == models.ErrInvalidRequest {
				code = http.StatusBadRequest
			}
			s.writeError(w, code, err.Error())
			return
		}

		s.writeJSON(w, http.StatusCreated, models.APIResponse{
			Code: 201,
			Data: role,
		})

	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleRoleByID(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/api/v1/roles/")
	if id == "" {
		s.writeError(w, http.StatusBadRequest, "role id required")
		return
	}

	switch r.Method {
	case http.MethodGet:
		role, err := s.roleService.GetRoleByID(id)
		if err != nil {
			s.writeError(w, http.StatusNotFound, err.Error())
			return
		}
		s.writeSuccess(w, role)

	case http.MethodDelete:
		err := s.roleService.DeleteRole(id)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrRoleNotFound {
				code = http.StatusNotFound
			}
			s.writeError(w, code, err.Error())
			return
		}
		s.writeSuccess(w, map[string]string{"status": "deleted"})

	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleResources(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		resources := s.resourceService.ListResources()
		s.writeSuccess(w, resources)

	case http.MethodPost:
		var req models.CreateResourceRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			s.writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}

		resource, err := s.resourceService.CreateResource(&req)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrResourceAlreadyExists {
				code = http.StatusConflict
			} else if err == models.ErrInvalidRequest {
				code = http.StatusBadRequest
			}
			s.writeError(w, code, err.Error())
			return
		}

		s.writeJSON(w, http.StatusCreated, models.APIResponse{
			Code: 201,
			Data: resource,
		})

	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleResourceByID(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/api/v1/resources/")
	if id == "" {
		s.writeError(w, http.StatusBadRequest, "resource id required")
		return
	}

	switch r.Method {
	case http.MethodGet:
		resource, err := s.resourceService.GetResourceByID(id)
		if err != nil {
			s.writeError(w, http.StatusNotFound, err.Error())
			return
		}
		s.writeSuccess(w, resource)

	case http.MethodDelete:
		err := s.resourceService.DeleteResource(id)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrResourceNotFound {
				code = http.StatusNotFound
			}
			s.writeError(w, code, err.Error())
			return
		}
		s.writeSuccess(w, map[string]string{"status": "deleted"})

	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleUserRoles(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
		var req models.AssignRoleRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			s.writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}

		err := s.roleService.AssignRoleToUser(req.UserID, req.RoleID)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrUserNotFound || err == models.ErrRoleNotFound {
				code = http.StatusNotFound
			}
			s.writeError(w, code, err.Error())
			return
		}

		s.writeSuccess(w, map[string]string{"status": "assigned"})

	case http.MethodDelete:
		var req models.AssignRoleRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			s.writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}

		err := s.roleService.RemoveRoleFromUser(req.UserID, req.RoleID)
		if err != nil {
			code := http.StatusInternalServerError
			if err == models.ErrUserNotFound {
				code = http.StatusNotFound
			}
			s.writeError(w, code, err.Error())
			return
		}

		s.writeSuccess(w, map[string]string{"status": "removed"})

	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}
