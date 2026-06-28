import { apiFetch, formatVND, statusText } from '/js/api.js';
import { isLoggedIn } from '/js/auth.js';

if (!isLoggedIn()) {
    window.location.href = '/login.html';
}

/* ===== order-confirmation.html ===== */
const confirmationTitle = document.getElementById('confirmation-title');
if (confirmationTitle) {
    const paymentResult = JSON.parse(sessionStorage.getItem('paymentResult'));
    const lastOrder = JSON.parse(sessionStorage.getItem('lastOrder'));

    if (paymentResult && paymentResult.success) {
        confirmationTitle.textContent = 'Thanh toán thành công!';
        document.getElementById('confirmation-msg').innerHTML = `
            <p>Mã giao dịch: <strong>${paymentResult.transactionId}</strong></p>
            <p>Cảm ơn bạn đã mua hàng. Email xác nhận đã được gửi.</p>
        `;
        document.getElementById('order-number').textContent = paymentResult.orderNumber;
        document.getElementById('order-total').textContent = formatVND(paymentResult.total);

        sessionStorage.removeItem('paymentResult');
        sessionStorage.removeItem('lastOrder');
        sessionStorage.removeItem('paymentMethod');
        sessionStorage.removeItem('idempotencyKey');
    } else if (lastOrder) {
        document.getElementById('order-number').textContent = lastOrder.orderNumber;
        document.getElementById('order-total').textContent = formatVND(lastOrder.total);
    } else {
        window.location.href = '/products.html';
    }
}

/* ===== order-history.html ===== */
const orderList = document.getElementById('order-list');
const pagination = document.getElementById('pagination');

if (orderList) {
    loadOrders();

    async function loadOrders(page = 0) {
        try {
            const data = await apiFetch(`/orders?page=${page}&size=10`);
            renderOrders(data, page);
        } catch (err) {
            orderList.innerHTML = '<p class="error">Không thể tải lịch sử đơn hàng</p>';
        }
    }

    function renderOrders(data, currentPage) {
        if (data.content.length === 0) {
            orderList.innerHTML = '<p>Chưa có đơn hàng nào.</p>';
            pagination.innerHTML = '';
            return;
        }

        let html = '<table><thead><tr><th>Mã đơn</th><th>Trạng thái</th><th>Tổng tiền</th><th>Ngày</th></tr></thead><tbody>';
        data.content.forEach(order => {
            const cls = order.status === 'CANCELLED' ? 'style="opacity:0.65;"'
                : order.status === 'DELIVERED' ? 'style="font-weight:600;"'
                : '';
            html += `<tr onclick="window.location='/order-detail.html?id=${order.id}'" ${cls}>
                <td>${order.orderNumber}</td>
                <td><span class="badge badge-${order.status.toLowerCase()}">${statusText(order.status)}</span></td>
                <td>${formatVND(order.total)}</td>
                <td>${new Date(order.createdAt).toLocaleDateString('vi-VN')}</td>
            </tr>`;
        });
        html += '</tbody></table>';
        orderList.innerHTML = html;

        window.loadOrders = loadOrders;

        if (data.totalPages > 1) {
            let pagHtml = '<div class="pagination">';
            for (let i = 0; i < data.totalPages; i++) {
                pagHtml += `<button onclick="loadOrders(${i})" class="${i === currentPage ? 'active' : ''}">${i + 1}</button>`;
            }
            pagHtml += '</div>';
            pagination.innerHTML = pagHtml;
        } else {
            pagination.innerHTML = '';
        }
    }
}

/* ===== order-detail.html ===== */
const orderDetail = document.getElementById('order-detail');
if (orderDetail) {
    loadOrderDetail();

    async function loadOrderDetail() {
        const params = new URLSearchParams(window.location.search);
        const orderId = params.get('id');

        if (!orderId) {
            orderDetail.innerHTML = '<p class="error">Không tìm thấy đơn hàng.</p>';
            return;
        }

        try {
            const order = await apiFetch(`/orders/${orderId}`);
            renderDetail(order);
        } catch (err) {
            orderDetail.innerHTML = '<p class="error">Không thể tải chi tiết đơn hàng.</p>';
        }
    }

    function renderDetail(order) {
        let html = `
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:1rem;">
                <h1 style="font-size:1.3rem;">Đơn hàng ${order.orderNumber}</h1>
                <span class="badge badge-${order.status.toLowerCase()}">${statusText(order.status)}</span>
            </div>
            <div style="color:var(--gray-600);font-size:0.85rem;margin-bottom:1rem;">
                <p>Ngày đặt: ${new Date(order.createdAt).toLocaleString('vi-VN')}</p>
                ${order.updatedAt ? `<p>Cập nhật: ${new Date(order.updatedAt).toLocaleString('vi-VN')}</p>` : ''}
            </div>
            <h3>Sản phẩm</h3>
            <table>
                <thead><tr><th>Sản phẩm</th><th>SL</th><th>Đơn giá</th><th>Thành tiền</th></tr></thead>
                <tbody>`;
        order.items.forEach(item => {
            html += `<tr>
                <td>${item.productImage ? `<img src="${item.productImage}" style="width:40px;height:40px;object-fit:cover;border-radius:4px;margin-right:0.5rem;vertical-align:middle;" />` : ''}${item.productName}</td>
                <td>${item.quantity}</td>
                <td>${formatVND(item.unitPrice)}</td>
                <td>${formatVND(item.subtotal)}</td>
            </tr>`;
        });
        html += `</tbody></table>
            <div class="order-summary">
                <p><span>Tạm tính</span><span>${formatVND(order.subtotal)}</span></p>
                <p><span>Phí vận chuyển</span><span>${formatVND(order.shippingFee)}</span></p>
                <p><span>Giảm giá</span><span>${formatVND(order.discount)}</span></p>
                <p style="font-size:1.1rem;font-weight:700;border-top:2px solid var(--gray-200);padding-top:0.5rem;margin-top:0.5rem;">
                    <span>Tổng cộng</span><span>${formatVND(order.total)}</span>
                </p>
            </div>`;

        if (order.notes) {
            html += `<div style="background:#fffbe6;border:1px solid #ffe58f;border-radius:8px;padding:1rem;margin:1rem 0;">
                <h3>Ghi chú</h3><p>${order.notes}</p>
            </div>`;
        }

        if (order.status === 'PENDING_PAYMENT') {
            html += `<button onclick="window.location='/payment.html?id=${order.id}'" class="btn btn-primary" style="margin-top:1rem;">
                Thanh toán ngay
            </button>`;
        }

        orderDetail.innerHTML = html;
    }
}
