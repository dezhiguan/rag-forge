import request from './request'

export const listEvalDatasets = () => request.get('/eval/datasets')
export const createEvalDataset = (data) => request.post('/eval/datasets', data)
export const getEvalDataset = (id) => request.get(`/eval/datasets/${id}`)
export const deleteEvalDataset = (id) => request.delete(`/eval/datasets/${id}`)

export const listEvalQuestions = (datasetId, page = 1, size = 20) =>
  request.get(`/eval/datasets/${datasetId}/questions`, { params: { page, size } })
export const createEvalQuestion = (datasetId, data) =>
  request.post(`/eval/datasets/${datasetId}/questions`, data)
export const updateEvalQuestion = (datasetId, questionId, data) =>
  request.put(`/eval/datasets/${datasetId}/questions/${questionId}`, data)
export const batchCreateEvalQuestions = (datasetId, questions) =>
  request.post(`/eval/datasets/${datasetId}/questions/batch`, questions)
export const saveQuestionFromSearch = (datasetId, data) =>
  request.post(`/eval/datasets/${datasetId}/questions/from-search`, data)
export const deleteEvalQuestion = (datasetId, questionId) =>
  request.delete(`/eval/datasets/${datasetId}/questions/${questionId}`)

export const runExperiment = (data) => request.post('/eval/experiments/run', data)
export const listExperiments = () => request.get('/eval/experiments')
export const getExperiment = (id) => request.get(`/eval/experiments/${id}`)
export const deleteExperiment = (id) => request.delete(`/eval/experiments/${id}`)
