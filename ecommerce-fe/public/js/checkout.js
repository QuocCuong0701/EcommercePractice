import { apiFetch, formatVND } from '/js/api.js';
import { isLoggedIn } from '/js/auth.js';

if (!isLoggedIn()) {
    window.location.href = '/login.html';
}

const form = document.getElementById('checkout-form');
const cartSummary = document.getElementById('cart-summary');

async function loadCartSummary() {
    try {
        const cart = await apiFetch('/cart');
        let html = `<p>Số lượng: <strong>${cart.itemCount}</strong> sản phẩm</p>`;
        html += '<table><thead><tr><th>Sản phẩm</th><th>SL</th><th>Đơn giá</th><th>Thành tiền</th></tr></thead><tbody>';
        cart.items.forEach(item => {
            html += `<tr>
                <td>${item.productName}</td>
                <td>${item.quantity}</td>
                <td>${formatVND(item.price)}</td>
                <td>${formatVND(item.price * item.quantity)}</td>
            </tr>`;
        });
        html += '</tbody></table>';
        html += `<p class="cart-total">Tổng cộng: <strong>${formatVND(cart.total)}</strong></p>`;
        cartSummary.innerHTML = html;
    } catch (err) {
        cartSummary.innerHTML = '<p class="error">Không thể tải giỏ hàng</p>';
    }
}

function generateIdempotencyKey() {
    return crypto.randomUUID();
}

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = document.getElementById('checkout-btn');
    btn.disabled = true;
    btn.textContent = 'Đang xử lý...';

    const idempotencyKey = generateIdempotencyKey();
    const paymentMethod = document.getElementById('paymentMethod').value;

    try {
        const response = await apiFetch('/orders/checkout', {
            method: 'POST',
            body: JSON.stringify({
                idempotencyKey,
                shippingAddress: {
                    fullName: document.getElementById('fullName').value,
                    phone: document.getElementById('phone').value,
                    address: document.getElementById('address').value,
                    province: document.getElementById('province').value,
                    district: document.getElementById('district').value,
                },
                notes: document.getElementById('notes').value,
            }),
        });

        sessionStorage.setItem('lastOrder', JSON.stringify(response));
        sessionStorage.setItem('paymentMethod', paymentMethod);
        sessionStorage.setItem('idempotencyKey', idempotencyKey);

        window.location.href = '/payment.html';
    } catch (err) {
        alert('Lỗi: ' + err.message);
        btn.disabled = false;
        btn.textContent = 'Đặt hàng';
    }
});

loadCartSummary();
