package testutil

import (
	"fmt"
	"math/rand"
	"time"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
)

var rng = rand.New(rand.NewSource(time.Now().UnixNano()))

type Factory struct {
	tenantCounter int
	userCounter   int
	spaceCounter  int
	docCounter    int
}

func NewFactory() *Factory {
	return &Factory{}
}

func (f *Factory) NextTenantName() string {
	f.tenantCounter++
	return fmt.Sprintf("Tenant_%s_%d", RandomString(6), f.tenantCounter)
}

func (f *Factory) NextUserName() string {
	f.userCounter++
	return fmt.Sprintf("user_%s_%d", RandomString(4), f.userCounter)
}

func (f *Factory) NextSpaceName() string {
	f.spaceCounter++
	return fmt.Sprintf("Space_%s_%d", RandomString(4), f.spaceCounter)
}

func (f *Factory) NextDocTitle() string {
	f.docCounter++
	return fmt.Sprintf("文档_%s_%d", RandomString(4), f.docCounter)
}

func RandomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[rng.Intn(len(letters))]
	}
	return string(b)
}

func RandomInt(min, max int) int {
	return min + rng.Intn(max-min+1)
}

func NewID() string {
	return uuid.New().String()
}

func HashPassword(pwd string) string {
	hash, _ := bcrypt.GenerateFromPassword([]byte(pwd), bcrypt.MinCost)
	return string(hash)
}

func (f *Factory) BuildTenant(opts ...func(*model.Tenant)) *model.Tenant {
	t := &model.Tenant{
		Name:        f.NextTenantName(),
		Domain:      fmt.Sprintf("%s.example.com", RandomString(8)),
		Namespace:   fmt.Sprintf("ns_%s", RandomString(8)),
		Description: "测试租户",
		Status:      "active",
	}
	for _, opt := range opts {
		opt(t)
	}
	if t.ID == "" {
		t.ID = NewID()
	}
	return t
}

func (f *Factory) BuildUser(tenantID string, opts ...func(*model.User)) *model.User {
	u := &model.User{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		Username:     f.NextUserName(),
		Email:        fmt.Sprintf("%s@test.com", RandomString(8)),
		FullName:     fmt.Sprintf("测试用户%s", RandomString(4)),
		PasswordHash: HashPassword("password123"),
		Status:       "active",
		Language:     "zh-CN",
		Timezone:     "Asia/Shanghai",
	}
	for _, opt := range opts {
		opt(u)
	}
	if u.ID == "" {
		u.ID = NewID()
	}
	return u
}

func (f *Factory) BuildSpace(tenantID, ownerID string, opts ...func(*model.Space)) *model.Space {
	s := &model.Space{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		Name:         f.NextSpaceName(),
		Namespace:    fmt.Sprintf("sp_%s", RandomString(8)),
		Description:  "测试空间",
		Status:       "active",
		Visibility:   "private",
		OwnerID:      ownerID,
		DefaultRoleID: "",
	}
	for _, opt := range opts {
		opt(s)
	}
	if s.ID == "" {
		s.ID = NewID()
	}
	return s
}

func (f *Factory) BuildDocument(tenantID, spaceID, authorID string, opts ...func(*model.Document)) *model.Document {
	d := &model.Document{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		SpaceID:      spaceID,
		Title:        f.NextDocTitle(),
		Summary:      "这是一篇测试文档的摘要",
		Content: model.ProseMirrorDoc{
			Type: "doc",
			Content: []map[string]interface{}{
				{
					"type": "paragraph",
					"content": []interface{}{
						map[string]interface{}{"type": "text", "text": "这是测试文档内容 Hello World"},
					},
				},
			},
		},
		ContentText:  "这是测试文档内容 Hello World",
		Status:       "published",
		Tags:         []string{"测试", "demo"},
		LangCode:     "zh-CN",
		CreatedBy:    authorID,
		UpdatedBy:    authorID,
		Version:      1,
		IsPublic:     false,
		Category:     "general",
	}
	for _, opt := range opts {
		opt(d)
	}
	if d.ID == "" {
		d.ID = NewID()
	}
	return d
}

func (f *Factory) BuildDocumentVersion(doc *model.Document, editorID string, opts ...func(*model.DocumentVersion)) *model.DocumentVersion {
	v := &model.DocumentVersion{
		TenantScoped: model.TenantScoped{TenantID: doc.TenantID},
		DocID:        doc.ID,
		SpaceID:      doc.SpaceID,
		Version:      doc.Version,
		Title:        doc.Title,
		Content:      doc.Content,
		ContentText:  doc.ContentText,
		CreatedBy:    editorID,
		ChangeLog:    "初始化版本",
	}
	for _, opt := range opts {
		opt(v)
	}
	if v.ID == "" {
		v.ID = NewID()
	}
	return v
}

func (f *Factory) BuildDirectory(tenantID, spaceID, creatorID string, opts ...func(*model.Directory)) *model.Directory {
	d := &model.Directory{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		SpaceID:      spaceID,
		Name:         fmt.Sprintf("目录_%s", RandomString(4)),
		Description:  "测试目录",
		CreatedBy:    creatorID,
		SortOrder:    0,
	}
	for _, opt := range opts {
		opt(d)
	}
	if d.ID == "" {
		d.ID = NewID()
	}
	return d
}

func (f *Factory) BuildPermission(
	tenantID string,
	resourceType model.ResourceType,
	resourceID string,
	role model.Role,
	subjectType model.SubjectType,
	subjectID string,
	opts ...func(*model.Permission),
) *model.Permission {
	p := &model.Permission{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		ResourceType: resourceType,
		ResourceID:   resourceID,
		Role:         role,
		SubjectType:  subjectType,
		SubjectID:    subjectID,
	}
	for _, opt := range opts {
		opt(p)
	}
	if p.ID == "" {
		p.ID = NewID()
	}
	return p
}

func (f *Factory) BuildQuota(tenantID string, opts ...func(*model.Quota)) *model.Quota {
	q := &model.Quota{
		TenantScoped:  model.TenantScoped{TenantID: tenantID},
		StorageLimit:  1024 * 1024 * 1024,
		StorageUsed:   0,
		DocLimit:      1000,
		DocCount:      0,
		UserLimit:     100,
		UserCount:     0,
		ApiCallLimit:  100000,
		ApiCallCount:  0,
		PlanType:      "pro",
	}
	for _, opt := range opts {
		opt(q)
	}
	if q.ID == "" {
		q.ID = NewID()
	}
	return q
}

func (f *Factory) BuildAttachment(tenantID, spaceID, uploaderID string, opts ...func(*model.Attachment)) *model.Attachment {
	a := &model.Attachment{
		TenantScoped:  model.TenantScoped{TenantID: tenantID},
		SpaceID:       spaceID,
		FileName:      fmt.Sprintf("%s.txt", RandomString(8)),
		OriginalName:  "测试文件.txt",
		FileType:      "text/plain",
		FileSize:      int64(RandomInt(100, 10000)),
		StoragePath:   fmt.Sprintf("tenant_%s/attachments/%s.txt", tenantID, RandomString(8)),
		StorageType:   "minio",
		UploadedBy:    uploaderID,
		DownloadCount: 0,
		IsParsed:      false,
		ParseStatus:   "pending",
	}
	for _, opt := range opts {
		opt(a)
	}
	if a.ID == "" {
		a.ID = NewID()
	}
	return a
}

func (f *Factory) BuildUserGroup(tenantID string, opts ...func(*model.UserGroup)) *model.UserGroup {
	g := &model.UserGroup{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		Name:         fmt.Sprintf("用户组_%s", RandomString(4)),
		Description:  "测试用户组",
		Type:         "custom",
	}
	for _, opt := range opts {
		opt(g)
	}
	if g.ID == "" {
		g.ID = NewID()
	}
	return g
}

func (f *Factory) BuildUserGroupMember(tenantID, groupID, userID string) *model.UserGroupMember {
	m := &model.UserGroupMember{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		GroupID:      groupID,
		UserID:       userID,
	}
	if m.ID == "" {
		m.ID = NewID()
	}
	return m
}

func (f *Factory) BuildDepartment(tenantID string, opts ...func(*model.Department)) *model.Department {
	d := &model.Department{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		Name:         fmt.Sprintf("部门_%s", RandomString(4)),
		Description:  "测试部门",
		SortOrder:    0,
	}
	for _, opt := range opts {
		opt(d)
	}
	if d.ID == "" {
		d.ID = NewID()
	}
	return d
}

func (f *Factory) BuildI18nDoc(tenantID, sourceDocID, srcLang, tgtLang string, opts ...func(*model.I18nDoc)) *model.I18nDoc {
	i := &model.I18nDoc{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		SourceDocID:  sourceDocID,
		SourceLang:   srcLang,
		TargetLang:   tgtLang,
		Status:       "draft",
		Progress:     0,
	}
	for _, opt := range opts {
		opt(i)
	}
	if i.ID == "" {
		i.ID = NewID()
	}
	return i
}

func (f *Factory) BuildTranslationMemory(tenantID, srcLang, tgtLang string, opts ...func(*model.TranslationMemory)) *model.TranslationMemory {
	tm := &model.TranslationMemory{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		SourceLang:   srcLang,
		TargetLang:   tgtLang,
		SourceText:   "这是源文本段落",
		TargetText:   "This is the target text paragraph",
		UsageCount:   0,
		Quality:      0.95,
		Domain:       "general",
	}
	for _, opt := range opts {
		opt(tm)
	}
	if tm.ID == "" {
		tm.ID = NewID()
	}
	return tm
}

func (f *Factory) BuildTheme(tenantID string, opts ...func(*model.Theme)) *model.Theme {
	th := &model.Theme{
		TenantScoped:   model.TenantScoped{TenantID: tenantID},
		Name:           fmt.Sprintf("主题_%s", RandomString(4)),
		IsDefault:      false,
		PrimaryColor:   "#1890ff",
		SecondaryColor: "#13c2c2",
		AccentColor:    "#722ed1",
		IsSystem:       false,
	}
	for _, opt := range opts {
		opt(th)
	}
	if th.ID == "" {
		th.ID = NewID()
	}
	return th
}

func (f *Factory) BuildApiToken(tenantID, userID string, opts ...func(*model.ApiToken)) *model.ApiToken {
	t := &model.ApiToken{
		TenantScoped: model.TenantScoped{TenantID: tenantID},
		Name:         fmt.Sprintf("API Token %s", RandomString(4)),
		Token:        fmt.Sprintf("kb_%s_%s", RandomString(8), RandomString(24)),
		TokenHash:    fmt.Sprintf("hash_%s", RandomString(32)),
		UserID:       userID,
		Permissions:  []string{"document:read", "document:write"},
		IsActive:     true,
	}
	for _, opt := range opts {
		opt(t)
	}
	if t.ID == "" {
		t.ID = NewID()
	}
	return t
}
