import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return <main style={{ minHeight: '100vh', display: 'grid', placeContent: 'center', textAlign: 'center' }}><h1>Không tìm thấy trang</h1><p>Địa chỉ bạn truy cập không tồn tại.</p><Link to="/">Về trang chủ</Link></main>
}
