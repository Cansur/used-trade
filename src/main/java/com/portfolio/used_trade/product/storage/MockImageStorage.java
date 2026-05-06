package com.portfolio.used_trade.product.storage;

import com.portfolio.used_trade.product.dto.PresignedUrlResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발/테스트용 Mock {@link ImageStoragePort} 구현.
 *
 * <p><b>역할</b>
 * <ul>
 *   <li>AWS 자격증명/네트워크 없이 인터페이스 흐름 검증</li>
 *   <li>응답 구조는 W2 의 진짜 S3 어댑터와 동일 → 클라이언트 코드는 변경 없이 그대로 사용 가능</li>
 *   <li>uploadUrl 은 fake 도메인 (`mock-s3.local`) — 실제 PUT 은 불가, 형식 검증만 가능</li>
 * </ul>
 *
 * <p><b>활성 조건</b>
 * {@code @Profile("!prod")} — 운영(prod) 외 모든 프로파일에서 활성. 운영에는 별도의
 * S3 어댑터(예정)가 같은 인터페이스를 구현해 빈으로 등록될 예정. 두 빈이 동시에
 * 등록되지 않도록 프로파일로 분리한다.
 *
 * <p><b>TTL 5분</b> — 정책 상수. 노출 시 위험을 줄이기 위해 짧게.
 */
@Component
@Profile("!prod")
public class MockImageStorage implements ImageStoragePort {

    /** Presigned URL 만료 시간(초). 운영 어댑터도 같은 값을 반환하면 클라 동일 동작. */
    public static final int EXPIRES_IN_SECONDS = 300;

    @Override
    public PresignedUrlResponse presign(String objectKey, String contentType) {
        // fake URL — 형식만 동일, 실제 호출 불가. content-type 도 쿼리에 박아 디버그성 노출.
        long expiresAtEpoch = (System.currentTimeMillis() / 1000) + EXPIRES_IN_SECONDS;
        String mockUrl = "https://mock-s3.local/used-trade/" + objectKey
                + "?expires=" + expiresAtEpoch
                + "&content-type=" + contentType
                + "&signature=mock-signature";
        return new PresignedUrlResponse(mockUrl, objectKey, EXPIRES_IN_SECONDS);
    }
}
