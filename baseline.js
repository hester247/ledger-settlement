import http from 'k6/http';

export const options = {
    vus: 200,
    duration: '60s',
};

export default function () {
    http.get('http://localhost:8080/payments/settlement?merchantId=MR-4471');
}