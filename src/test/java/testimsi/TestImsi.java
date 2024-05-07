package testimsi;

import java.util.Map;
import static spark.Spark.*;

public class TestImsi {

    public static void main(String[] args) {

        //test1();

        test2();

    }


    public static void test2() {

        get("/hello/:name", (request, response) -> {
            return "Hello: " + request.queryParams("name");
        });
    }

    public static void test1() {

        // 시스템 환경 변수 맵 가져오기
        Map<?, ?> env = System.getProperties();

        // StringBuilder를 사용하여 환경 변수 문자열 생성
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> entry : env.entrySet()) {
            // 각 환경 변수를 key=value 형태로 추가하고, 줄바꿈 문자로 구분
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }

        // 생성된 문자열 출력
        String envString = sb.toString();
        System.out.println(envString);
    }

}
