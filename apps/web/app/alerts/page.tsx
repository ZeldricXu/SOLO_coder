'use client';

import { useState, useEffect } from 'react';
import { Plus, AlertTriangle, AlertCircle, Info, CheckCircle, Clock, Bell, Search, X, Check } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { formatDate, formatRelativeTime, getSeverityColor, getStatusColor, cn } from '@/lib/utils';
import type { Alert } from '@mlops/shared';

export default function AlertsPage() {
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [events, setEvents] = useState<any[]>([]);
  const [selectedAlert, setSelectedAlert] = useState<Alert | null>(null);
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [filterSeverity, setFilterSeverity] = useState<string>('all');
  const [filterStatus, setFilterStatus] = useState<string>('all');

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    try {
      const [alertsRes] = await Promise.all([
        api.monitoring.listAlerts({ pageSize: 20 }),
      ]);
      setAlerts(alertsRes.data.data);

      const mockEvents = [
        { id: 1, alertName: 'P99 Latency > 200ms', severity: 'critical', message: 'P99 latency exceeded threshold: 254ms > 200ms', timestamp: Date.now() - 300000 },
        { id: 2, alertName: 'Prediction Drift Detected', severity: 'warning', message: 'p-value 0.02 < 0.05 for prediction feature', timestamp: Date.now() - 1200000 },
        { id: 3, alertName: 'Error Rate > 1%', severity: 'critical', message: 'Error rate reached 1.5% in last 5 minutes', timestamp: Date.now() - 3600000 },
        { id: 4, alertName: 'Low Throughput', severity: 'info', message: 'Throughput below expected baseline', timestamp: Date.now() - 7200000 },
      ];
      setEvents(mockEvents);
    } catch (error) {
      console.error('Failed to fetch alerts:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateAlert = async (e: React.FormEvent) => {
    e.preventDefault();
    const form = e.target as HTMLFormElement;
    const formData = new FormData(form);

    try {
      await api.monitoring.createAlert({
        name: formData.get('name') as string,
        type: formData.get('type') as any,
        severity: formData.get('severity') as any,
        threshold: {
          metric: formData.get('metric') as string,
          operator: formData.get('operator') as any,
          value: parseFloat(formData.get('value') as string),
          durationMinutes: parseInt(formData.get('duration') as string),
          percentile: parseFloat(formData.get('percentile') as string) || 99,
        },
        condition: { type: 'single' },
        notificationChannels: [{ type: 'email', config: { email: 'alerts@company.com' }, enabled: true }],
      });
      toast.success('Alert created');
      setShowCreateModal(false);
      fetchData();
    } catch (error) {
      toast.error('Failed to create alert');
    }
  };

  const handleAcknowledge = async (alert: Alert) => {
    try {
      await api.monitoring.updateAlertStatus(alert.id, 'acknowledged', 'admin');
      toast.success('Alert acknowledged');
      fetchData();
    } catch (error) {
      toast.error('Failed to acknowledge alert');
    }
  };

  const handleResolve = async (alert: Alert) => {
    const reason = prompt('Enter resolution reason:');
    if (reason === null) return;
    try {
      await api.monitoring.updateAlertStatus(alert.id, 'resolved', 'admin', reason);
      toast.success('Alert resolved');
      fetchData();
    } catch (error) {
      toast.error('Failed to resolve alert');
    }
  };

  const filteredAlerts = alerts.filter((a) => {
    const matchesSearch = a.name.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesSeverity = filterSeverity === 'all' || a.severity === filterSeverity;
    const matchesStatus = filterStatus === 'all' || a.status === filterStatus;
    return matchesSearch && matchesSeverity && matchesStatus;
  });

  const activeAlerts = alerts.filter((a) => a.status === 'active');
  const criticalCount = activeAlerts.filter((a) => a.severity === 'critical').length;
  const warningCount = activeAlerts.filter((a) => a.severity === 'warning').length;

  const getAlertIcon = (type: string) => {
    switch (type) {
      case 'inference_latency':
      case 'throughput':
        return <Clock className="w-5 h-5" />;
      case 'error_rate':
        return <AlertCircle className="w-5 h-5" />;
      case 'model_drift':
      case 'feature_drift':
        return <TrendingDown className="w-5 h-5" />;
      default:
        return <Bell className="w-5 h-5" />;
    }
  };

  if (loading) {
    return <div className="flex justify-center p-12"><div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" /></div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Alerts</h1>
          <p className="text-gray-500 mt-1">Manage and respond to system alerts</p>
        </div>
        <button onClick={() => setShowCreateModal(true)} className="btn-primary flex items-center gap-2">
          <Plus className="w-4 h-4" /> Create Alert
        </button>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <div className={cn(
          'card border-l-4',
          criticalCount > 0 ? 'border-l-red-500' : 'border-l-gray-200'
        )}>
          <div className="flex items-center gap-3">
            <div className={cn(
              'w-10 h-10 rounded-lg flex items-center justify-center',
              criticalCount > 0 ? 'bg-red-100' : 'bg-gray-100'
            )}>
              <AlertCircle className={cn('w-5 h-5', criticalCount > 0 ? 'text-red-600' : 'text-gray-600')} />
            </div>
            <div>
              <p className="text-2xl font-bold">{criticalCount}</p>
              <p className="text-sm text-gray-500">Critical Alerts</p>
            </div>
          </div>
        </div>

        <div className={cn(
          'card border-l-4',
          warningCount > 0 ? 'border-l-yellow-500' : 'border-l-gray-200'
        )}>
          <div className="flex items-center gap-3">
            <div className={cn(
              'w-10 h-10 rounded-lg flex items-center justify-center',
              warningCount > 0 ? 'bg-yellow-100' : 'bg-gray-100'
            )}>
              <AlertTriangle className={cn('w-5 h-5', warningCount > 0 ? 'text-yellow-600' : 'text-gray-600')} />
            </div>
            <div>
              <p className="text-2xl font-bold">{warningCount}</p>
              <p className="text-sm text-gray-500">Warning Alerts</p>
            </div>
          </div>
        </div>

        <div className="card border-l-4 border-l-green-500">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center">
              <CheckCircle className="w-5 h-5 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{alerts.filter((a) => a.status === 'resolved').length}</p>
              <p className="text-sm text-gray-500">Resolved Today</p>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2">
          <div className="flex items-center gap-3 mb-4">
            <div className="relative flex-1">
              <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                placeholder="Search alerts..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="input pl-10"
              />
            </div>
            <select
              value={filterSeverity}
              onChange={(e) => setFilterSeverity(e.target.value)}
              className="input max-w-xs"
            >
              <option value="all">All Severities</option>
              <option value="critical">Critical</option>
              <option value="warning">Warning</option>
              <option value="info">Info</option>
            </select>
            <select
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
              className="input max-w-xs"
            >
              <option value="all">All Statuses</option>
              <option value="active">Active</option>
              <option value="acknowledged">Acknowledged</option>
              <option value="resolved">Resolved</option>
            </select>
          </div>

          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="table-header text-xs">Alert</th>
                  <th className="table-header text-xs">Type</th>
                  <th className="table-header text-xs">Severity</th>
                  <th className="table-header text-xs">Status</th>
                  <th className="table-header text-xs">Triggered</th>
                  <th className="table-header text-xs">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {filteredAlerts.map((alert) => (
                  <tr
                    key={alert.id}
                    className={cn(
                      'hover:bg-gray-50 cursor-pointer transition-colors',
                      selectedAlert?.id === alert.id && 'bg-primary-50'
                    )}
                    onClick={() => setSelectedAlert(alert)}
                  >
                    <td className="table-cell">
                      <div className="flex items-center gap-3">
                        <div className={cn(
                          'w-8 h-8 rounded-lg flex items-center justify-center',
                          alert.severity === 'critical' ? 'bg-red-100 text-red-600' :
                          alert.severity === 'warning' ? 'bg-yellow-100 text-yellow-600' :
                          'bg-blue-100 text-blue-600'
                        )}>
                          {getAlertIcon(alert.type)}
                        </div>
                        <div>
                          <p className="font-medium">{alert.name}</p>
                          {alert.modelId && (
                            <p className="text-xs text-gray-500">Model: {alert.modelId.slice(0, 8)}</p>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="table-cell text-sm capitalize">{alert.type.replace('_', ' ')}</td>
                    <td className="table-cell">
                      <span className={cn('badge', getSeverityColor(alert.severity))}>
                        {alert.severity}
                      </span>
                    </td>
                    <td className="table-cell">
                      <span className={cn('badge', getStatusColor(alert.status === 'acknowledged' ? 'running' : alert.status))}>
                        {alert.status}
                      </span>
                    </td>
                    <td className="table-cell text-sm text-gray-500">
                      {alert.lastTriggeredAt ? formatRelativeTime(alert.lastTriggeredAt) : '-'}
                    </td>
                    <td className="table-cell">
                      <div className="flex items-center gap-1">
                        {alert.status === 'active' && (
                          <>
                            <button
                              onClick={(e) => { e.stopPropagation(); handleAcknowledge(alert); }}
                              className="p-1.5 hover:bg-yellow-50 rounded"
                              title="Acknowledge"
                            >
                              <Check className="w-4 h-4 text-yellow-600" />
                            </button>
                            <button
                              onClick={(e) => { e.stopPropagation(); handleResolve(alert); }}
                              className="p-1.5 hover:bg-green-50 rounded"
                              title="Resolve"
                            >
                              <CheckCircle className="w-4 h-4 text-green-600" />
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className="space-y-6">
          {selectedAlert && (
            <div className="card">
              <div className="flex items-center justify-between mb-4">
                <h3 className="font-semibold">{selectedAlert.name}</h3>
                <button onClick={() => setSelectedAlert(null)}>
                  <X className="w-4 h-4 text-gray-400" />
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <p className="text-xs text-gray-500 mb-1">Description</p>
                  <p className="text-sm">{selectedAlert.description || 'No description'}</p>
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div className="p-3 bg-gray-50 rounded-lg">
                    <p className="text-xs text-gray-500">Type</p>
                    <p className="font-medium text-sm capitalize">{selectedAlert.type.replace('_', ' ')}</p>
                  </div>
                  <div className="p-3 bg-gray-50 rounded-lg">
                    <p className="text-xs text-gray-500">Trigger Count</p>
                    <p className="font-medium text-sm">{selectedAlert.triggerCount}</p>
                  </div>
                </div>

                <div>
                  <p className="text-xs text-gray-500 mb-2">Threshold</p>
                  <div className="p-3 bg-gray-50 rounded-lg text-sm font-mono">
                    {selectedAlert.threshold.metric}{' '}
                    {selectedAlert.threshold.operator}{' '}
                    {selectedAlert.threshold.value}
                  </div>
                </div>

                {selectedAlert.lastTriggeredAt && (
                  <div>
                    <p className="text-xs text-gray-500 mb-2">Last Triggered</p>
                    <p className="text-sm">{formatDate(selectedAlert.lastTriggeredAt)}</p>
                  </div>
                )}

                {selectedAlert.status === 'active' && (
                  <div className="flex gap-2">
                    <button
                      onClick={() => handleAcknowledge(selectedAlert)}
                      className="btn-secondary flex-1 flex items-center justify-center gap-2"
                    >
                      <Check className="w-4 h-4" /> Acknowledge
                    </button>
                    <button
                      onClick={() => handleResolve(selectedAlert)}
                      className="btn-primary flex-1 flex items-center justify-center gap-2"
                    >
                      <CheckCircle className="w-4 h-4" /> Resolve
                    </button>
                  </div>
                )}
              </div>
            </div>
          )}

          <div className="card">
            <h3 className="font-semibold mb-4">Recent Events</h3>
            <div className="space-y-3">
              {events.map((event) => (
                <div key={event.id} className="flex items-start gap-3 p-3 bg-gray-50 rounded-lg">
                  <div className={cn(
                    'w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0',
                    event.severity === 'critical' ? 'bg-red-100 text-red-600' :
                    event.severity === 'warning' ? 'bg-yellow-100 text-yellow-600' :
                    'bg-blue-100 text-blue-600'
                  )}>
                    <Bell className="w-4 h-4" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <p className="text-sm font-medium">{event.alertName}</p>
                      <span className={cn('badge text-xs', getSeverityColor(event.severity))}>
                        {event.severity}
                      </span>
                    </div>
                    <p className="text-xs text-gray-500 mt-1 truncate">{event.message}</p>
                    <p className="text-xs text-gray-400 mt-1">{formatRelativeTime(event.timestamp)}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {showCreateModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl p-6 w-full max-w-lg max-h-[90vh] overflow-y-auto">
            <h2 className="text-xl font-bold mb-6">Create New Alert</h2>
            <form onSubmit={handleCreateAlert} className="space-y-4">
              <div>
                <label className="label">Alert Name</label>
                <input type="text" name="name" className="input" placeholder="e.g. P99 Latency Threshold" required />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="label">Type</label>
                  <select name="type" className="input" required>
                    <option value="inference_latency">Inference Latency</option>
                    <option value="error_rate">Error Rate</option>
                    <option value="throughput">Throughput</option>
                    <option value="model_drift">Model Drift</option>
                    <option value="feature_drift">Feature Drift</option>
                  </select>
                </div>
                <div>
                  <label className="label">Severity</label>
                  <select name="severity" className="input" required>
                    <option value="info">Info</option>
                    <option value="warning">Warning</option>
                    <option value="critical">Critical</option>
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label className="label">Metric</label>
                  <input type="text" name="metric" className="input" placeholder="p99_latency" required />
                </div>
                <div>
                  <label className="label">Operator</label>
                  <select name="operator" className="input" required>
                    <option value="gt">Greater Than</option>
                    <option value="lt">Less Than</option>
                    <option value="gte">Greater or Equal</option>
                    <option value="lte">Less or Equal</option>
                  </select>
                </div>
                <div>
                  <label className="label">Threshold</label>
                  <input type="number" name="value" className="input" placeholder="200" required />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="label">Duration (min)</label>
                  <input type="number" name="duration" className="input" defaultValue={5} required />
                </div>
                <div>
                  <label className="label">Percentile</label>
                  <input type="number" name="percentile" className="input" defaultValue={99} />
                </div>
              </div>
              <div className="flex gap-3 pt-4">
                <button type="button" onClick={() => setShowCreateModal(false)} className="btn-secondary flex-1">
                  Cancel
                </button>
                <button type="submit" className="btn-primary flex-1">
                  Create Alert
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

function TrendingDown({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <polyline points="23 18 13.5 8.5 8.5 13.5 1 6" />
      <polyline points="17 18 23 18 23 12" />
    </svg>
  );
}
