import http from "k6/http";

export const options = {
    scenarios: {
        steady_load: {
            executor: "constant-arrival-rate",
            rate: 10,
            timeUnit: "1s",
            duration: "10m",
            preAllocatedVUs: 20,
            maxVUs: 50
        }
    }
};

export default function () {
    http.get("http://localhost:8080/payments/settlement?merchantId=MR-4471");
}
