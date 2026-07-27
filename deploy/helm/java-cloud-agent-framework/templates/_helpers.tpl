{{/*
Expand the name of the chart.
*/}}
{{- define "java-cloud-agent-framework.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "java-cloud-agent-framework.fullname" -}}
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

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "java-cloud-agent-framework.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "java-cloud-agent-framework.labels" -}}
helm.sh/chart: {{ include "java-cloud-agent-framework.chart" . }}
{{ include "java-cloud-agent-framework.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "java-cloud-agent-framework.selectorLabels" -}}
app.kubernetes.io/name: {{ include "java-cloud-agent-framework.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "java-cloud-agent-framework.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "java-cloud-agent-framework.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Name of the Secret to reference from the Deployment — either the chart's
own generated Secret, or an externally pre-created one (see
values.secrets.secretRefName / values.secrets.create).
*/}}
{{- define "java-cloud-agent-framework.secretName" -}}
{{- if .Values.secrets.create }}
{{- include "java-cloud-agent-framework.fullname" . }}
{{- else }}
{{- required "secrets.secretRefName is required when secrets.create=false" .Values.secrets.secretRefName }}
{{- end }}
{{- end }}
