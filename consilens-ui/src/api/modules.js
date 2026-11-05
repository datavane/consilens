import request from "./index";

// ===== 任务 =====

export function getDiffReport(taskId, config) {
  return request.get(`/tasks/${taskId}/diff-report`, config || {});
}

export function retryTask(taskId) {
  return request.post(`/tasks/${taskId}/retry`);
}

export function cancelTask(taskId) {
  return request.post(`/tasks/${taskId}/cancel`);
}

// ===== artifact =====

export function listArtifacts(params) {
  return request.get("/artifacts", { params });
}

export function getArtifactContent(artifactId, config) {
  return request.get(`/artifacts/${artifactId}/content`, config || {});
}

export function listArtifactDifferences(artifactId, params) {
  return request.get(`/artifacts/${artifactId}/differences`, { params });
}

// ===== 节点 =====

export function listNodes() {
  return request.get("/nodes");
}

export function getNode(nodeKey) {
  return request.get(`/nodes/${nodeKey}`);
}

// ===== 连接测试 =====

export function testConnection(payload, config) {
  return request.post("/connections/test", payload, config || {});
}

// ===== 数据源 =====

export function listDatasourceTypes() {
  return request.get("/datasources/types");
}

// 数据源类型的连接参数模板：返回 [{field,title,type,placeholder,required,defaultValue,rows,options}]
export function getDatasourceTypeConfig(type) {
  return request.get(`/datasources/types/${encodeURIComponent(type)}/config`);
}

export function listDatasources() {
  return request.get("/datasources");
}

export function createDatasource(payload) {
  return request.post("/datasources", payload);
}

export function updateDatasource(id, payload) {
  return request.put(`/datasources/${id}`, payload);
}

export function deleteDatasource(id) {
  return request.delete(`/datasources/${id}`);
}

export function testDatasource(id) {
  return request.post(`/datasources/${id}/test`);
}

export function listDatasourceDatabases(id) {
  return request.get(`/datasources/${id}/databases`);
}

export function listDatasourceTables(id, database) {
  return request.get(`/datasources/${id}/databases/${encodeURIComponent(database)}/tables`);
}

export function listDatasourceColumns(id, database, table) {
  return request.get(
    `/datasources/${id}/databases/${encodeURIComponent(database)}/tables/${encodeURIComponent(table)}/columns`
  );
}

// ===== 任务定义 =====

export function listTaskDefinitions(params) {
  return request.get("/task-definitions", { params });
}

export function createTaskDefinition(payload) {
  return request.post("/task-definitions", payload);
}

export function getTaskDefinition(id) {
  return request.get(`/task-definitions/${id}`);
}

export function updateTaskDefinition(id, payload) {
  return request.put(`/task-definitions/${id}`, payload);
}

export function deleteTaskDefinition(id) {
  return request.delete(`/task-definitions/${id}`);
}

export function runTaskDefinition(id, payload) {
  return request.post(`/task-definitions/${id}/run`, payload || {});
}

export function toggleTaskDefinition(id, enabled) {
  return request.post(`/task-definitions/${id}/toggle`, null, { params: { enabled } });
}

// ===== 运行实例 =====

export function listTaskInstances(params) {
  return request.get("/task-instances", { params });
}

export function getTaskInstance(id) {
  return request.get(`/task-instances/${id}`);
}

// 运行实例日志：tailLines 读末尾并返回总行数，offset+limit 做增量拉取
export function getTaskLogs(id, params) {
  return request.get(`/task-instances/${id}/logs`, { params, silent: true });
}

export function downloadTaskLog(id) {
  return request.get(`/task-instances/${id}/logs/download`, { responseType: "blob" });
}

// ===== 工作台 =====

export function getDashboardSummary() {
  return request.get("/dashboard/summary");
}
