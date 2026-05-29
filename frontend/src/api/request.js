import axios from 'axios'

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 0,
  headers: {
    'X-API-Key': 'sk-ragforge-dev',
  },
})

request.interceptors.response.use(
  (res) => {
    if (res.config?.responseType === 'blob') {
      return res
    }
    const body = res.data
    if (body && typeof body.code === 'number' && body.code !== 200) {
      alert('请求失败: ' + (body.msg || '未知错误'))
      return Promise.reject(body)
    }
    return body
  },
  (err) => {
    alert('请求失败: ' + (err.response?.data?.msg || err.message))
    return Promise.reject(err)
  }
)

export default request
