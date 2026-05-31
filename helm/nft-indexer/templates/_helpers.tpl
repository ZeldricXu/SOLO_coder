{{/* Expand the name of the chart. */}}
{{- define "nft-indexer.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Create a fully qualified app name. */}}
{{- define "nft-indexer.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/* Create chart name and version as used by the chart label. */}}
{{- define "nft-indexer.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Common labels */}}
{{- define "nft-indexer.labels" -}}
helm.sh/chart: {{ include "nft-indexer.chart" . }}
{{ include "nft-indexer.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* Selector labels */}}
{{- define "nft-indexer.selectorLabels" -}}
app.kubernetes.io/name: {{ include "nft-indexer.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* Generate the service account name */}}
{{- define "nft-indexer.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "nft-indexer.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/* Java JVM Options */}}
{{- define "nft-indexer.javaOpts" -}}
{{- $opts := list -}}
{{- $opts = append $opts (printf "-Xms%s" .Values.javaOpts.xms) -}}
{{- $opts = append $opts (printf "-Xmx%s" .Values.javaOpts.xmx) -}}
{{- $opts = append $opts (printf "-XX:MaxMetaspaceSize=%s" .Values.javaOpts.maxMetaspaceSize) -}}
{{- if eq .Values.javaOpts.gc.collector "G1" -}}
{{- $opts = append $opts "-XX:+UseG1GC" -}}
{{- $opts = append $opts (printf "-XX:MaxGCPauseMillis=%s" .Values.javaOpts.gc.maxGCPauseMillis) -}}
{{- end -}}
{{- with .Values.javaOpts.extraOpts -}}
{{- $opts = append $opts . -}}
{{- end -}}
{{- join " " $opts }}
{{- end }}

{{/* Database URL helper */}}
{{- define "nft-indexer.databaseUrl" -}}
{{- if .Values.externalDatabase.host }}
r2dbc:mysql://{{ .Values.externalDatabase.host }}:{{ .Values.externalDatabase.port }}/{{ .Values.externalDatabase.database }}?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
{{- else }}
r2dbc:mysql://{{ .Release.Name }}-mysql:3306/{{ .Values.mysql.auth.database }}?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
{{- end }}
{{- end }}

{{/* Redis Host helper */}}
{{- define "nft-indexer.redisHost" -}}
{{- if .Values.externalRedis.host }}
{{ .Values.externalRedis.host }}
{{- else }}
{{ .Release.Name }}-redis-master
{{- end }}
{{- end }}
