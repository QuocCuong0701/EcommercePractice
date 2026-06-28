import { apiFetch, formatVND } from '/js/api.js';
import { isLoggedIn } from '/js/auth.js';

if (!isLoggedIn()) {
    window.location.href = '/login.html';
}

const lastOrder = JSON.parse(sessionStorage.getItem('lastOrder'));
const idempotencyKey = sessionStorage.getItem('idempotencyKey');
const paymentMethod = sessionStorage.getItem('paymentMethod');

if (!lastOrder) {
    window.location.href = '/products.html';
}

const orderInfo = document.getElementById('order-info');
const payBtn = document.getElementById('pay-btn');
const payResult = document.getElementById('pay-result');

const methodNames = {
    COD: 'Thanh toán khi nhận hàng',
    BANK_TRANSFER: 'Chuyển khoản ngân hàng',
    VNPAY: 'VNPay',
    MOMO: 'Ví MoMo',
};

orderInfo.innerHTML = `
    <p>Mã đơn hàng: <strong>${lastOrder.orderNumber}</strong></p>
    <p>Số tiền: <strong>${formatVND(lastOrder.total)}</strong></p>
    <p>Phương thức: <strong>${methodNames[paymentMethod] || paymentMethod}</strong></p>
`;

payBtn.addEventListener('click', async () => {
    payBtn.disabled = true;
    payBtn.textContent = 'Đang xử lý thanh toán...';

    try {
        const response = await apiFetch('/payments/confirm', {
            method: 'POST',
            body: JSON.stringify({
                orderId: lastOrder.orderId,
                paymentMethod: paymentMethod || 'COD',
                idempotencyKey,
            }),
        });

        sessionStorage.setItem('paymentResult', JSON.stringify({
            ...response,
            orderNumber: lastOrder.orderNumber,
            total: lastOrder.total,
        }));

        window.location.href = '/order-confirmation.html';
    } catch (err) {
        payResult.innerHTML = `<p class="error">Thanh toán thất bại: ${err.message}</p>`;
        payBtn.disabled = false;
        payBtn.textContent = 'Thử lại';
    }
});
