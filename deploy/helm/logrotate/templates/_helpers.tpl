{{/*
Expand the name of the chart.
*/}}
{{- define "logrotate.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "logrotate.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Chart name and version as used by the chart label.
*/}}
{{- define "logrotate.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels
*/}}
{{- define "logrotate.labels" -}}
helm.sh/chart: {{ include "logrotate.chart" . }}
app.kubernetes.io/name: {{ include "logrotate.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/component: "logrotate"
app.kubernetes.io/part-of: "logrotate"
{{- if .Values.versionLabel }}
app.kubernetes.io/version: {{ .Values.versionLabel | quote }}
{{- end }}
{{- end -}}

{{/*
Selector labels
*/}}
{{- define "logrotate.selectorLabels" -}}
app.kubernetes.io/name: {{ include "logrotate.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Create the name of the service account to use
*/}}
{{- define "logrotate.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
    {{ default (include "logrotate.fullname" .) .Values.serviceAccount.name }}
{{- else -}}
    {{ default "default" .Values.serviceAccount.name }}
{{- end -}}
{{- end -}}

{{/*
PDB apiVersion
*/}}
{{- define "pdb.apiVersion" -}}
{{- if semverCompare ">=1.21-0" .Capabilities.KubeVersion.GitVersion -}}
policy/v1
{{- else -}}
policy/v1beta1
{{- end -}}
{{- end -}}

{{/*
HPA apiVersion
*/}}
{{- define "hpa.apiVersion" -}}
{{- if semverCompare ">=1.23-0" .Capabilities.KubeVersion.GitVersion -}}
autoscaling/v2
{{- else if semverCompare ">=1.22-0" .Capabilities.KubeVersion.GitVersion -}}
autoscaling/v2beta2
{{- else -}}
autoscaling/v2beta1
{{- end -}}
{{- end -}}
