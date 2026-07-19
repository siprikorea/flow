# DataFlow Editor

비주얼 데이터 변환 파이프라인 에디터. 모듈(입력부/처리부/출력부)을 캔버스에 드래그 앤 드랍으로 배치하고, 출력 포트 → 입력 포트를 베지어 곡선으로 연결하여 데이터 흐름을 구성한다. 시작/중지로 실행 시뮬레이션(패킷이 곡선을 따라 흐르는 애니메이션 + 모듈 상태 표시)을 수행하며, 플로우는 자동 저장 및 JSON 내보내기/불러오기를 지원한다.

**Compose Multiplatform**(Kotlin)으로 구현되었다. UI·상태·로직 전체가 `commonMain`에 있고, `jvmMain`은 진입점과 파일 입출력만 담당한다. 현재 데스크톱(JVM) 타깃으로 실행된다.

## 요구 사항
- JDK 17 이상 (툴체인은 17로 고정)
- Gradle은 wrapper(`./gradlew`, 9.3.1 고정)로 실행하므로 별도 설치 불필요

## 실행 / 빌드

```bash
./gradlew :composeApp:run              # 데스크톱 앱 실행
./gradlew :composeApp:compileKotlinJvm # 컴파일만
./gradlew :composeApp:packageDistributionForCurrentOs  # 네이티브 배포 패키지
```

## 프로젝트 구조

```
dataflow_editor/
├── settings.gradle.kts          # rootProject "dataflow-editor", :composeApp 포함
├── gradle.properties
├── gradlew / gradlew.bat        # Gradle wrapper (9.3.1)
└── composeApp/
    ├── build.gradle.kts         # Kotlin Multiplatform + Compose + serialization
    └── src/
        ├── commonMain/kotlin/dataflow/
        │   ├── Model.kt         # Node / Edge / PortRef / FlowFile / SavedState / Sel
        │   ├── Registry.kt      # 모듈 정의(플러그인 레지스트리) + ModuleDef
        │   ├── I18n.kt          # KO/EN 딕셔너리
        │   ├── Geometry.kt      # 격자 스냅, 포트 위치, 베지어, 기본 크기
        │   ├── Palette.kt       # 디자인 토큰(색상)
        │   ├── Platform.kt      # expect: 저장/파일 IO/시각
        │   ├── EditorState.kt   # 상태·히스토리·시뮬레이션·연결·자동배치 등 전 로직
        │   ├── Widgets.kt       # Txt / DtxField / hover 헬퍼
        │   ├── App.kt           # 메뉴바 · 사이드바 · 상태바 · 키보드
        │   ├── CanvasView.kt    # 캔버스 · 노드 · 포트 · 연결선 · 패킷 · 미니맵
        │   └── PropsPanel.kt    # 속성 패널
        └── jvmMain/kotlin/dataflow/
            ├── Main.kt          # application 진입점, 윈도우
            └── Platform.jvm.kt  # actual: ~/.dataflow-editor/flow.json, AWT 파일 다이얼로그
```

## 화면 구성 (단일 에디터 화면, 100vh)
1. **메뉴바** — 로고, File/Edit/창 드롭다운, 시작 / 선택 실행 / 중지 버튼, KO/EN 토글
2. **모듈 리스트 사이드바** — 내장 모듈 + 설치된 플러그인 카드(드래그하여 캔버스로 드랍)
3. **캔버스** — 격자 점 배경, 노드·연결선·패킷, 우하단 미니맵
4. **속성 패널** — 선택한 노드(이름/ID/파라미터/포트 편집)·연결선(from → to, 삭제)
5. **상태바** — 조작 힌트, 모듈·연결 수, 줌 컨트롤, 자동 저장 시각

## 주요 동작
- **모듈 추가**: 사이드바 카드를 드래그해 캔버스에 드랍 → 월드 좌표 변환 후 격자 스냅하여 생성
- **노드 드래그/리사이즈**: 헤더 드래그로 이동, 우하단 핸들로 크기 조절(min 120×60), 실시간 격자 스냅, 종료 시 히스토리 1회 기록
- **연결 생성**: 출력 포트에서 드래그 → 점선 프리뷰 곡선 → 입력 포트에서 놓으면 엣지 생성(같은 노드 금지, 입력 포트당 1개 — 기존 것 교체)
- **선택/삭제**: 노드·엣지 클릭 선택, Delete/Backspace 삭제, Escape로 연결 취소·메뉴 닫기
- **팬/줌**: Space + 드래그로 팬, 휠로 커서 기준 줌(0.3–2.5)
- **Undo/Redo**: Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y (스냅샷 스택 최대 60)
- **실행 시뮬레이션**: 소스 노드(입력 엣지 없음)부터 시작 → 노드 실행(900ms) → 나가는 엣지 활성(850ms, 패킷 애니메이션) → 다음 노드. 입력 포트가 있는데 연결이 없으면 error. "선택 실행"은 선택 노드부터 직접 시작(입력 검사 면제). 대기 타이머가 0이 되면 자동 종료
- **자동 배치**(Edit 메뉴): BFS 깊이별 컬럼 배치(x = 60 + depth·280)
- **다국어**: KO/EN 전환(UI 전체), 선택 언어 저장

## 상태 · 영속화
- `Node{id, type, label, x, y, w, h, inputs[], outputs[], params, status}` / `Edge{id, from, to, active}`
- 노드 id 변경 시 참조하는 모든 엣지 동기화. 포트 이름 변경/삭제도 동일
- 자동 저장: 상태 변경을 350ms 디바운스하여 `~/.dataflow-editor/flow.json`에 기록
- JSON 내보내기: `{version, nodes, edges, seq}` / 불러오기: AWT 파일 다이얼로그

## 모듈 정의 (플러그인 레지스트리)
[Registry.kt](composeApp/src/commonMain/kotlin/dataflow/Registry.kt)의 `ModuleDef` 배열이 곧 플러그인 목록 — 외부 플러그인은 이 레지스트리에 등록되면 리스트에 나타난다.
- 내장: csv(0→1), filter(1→2: pass/fail), map(1→1), merge(2→1: a,b), split(1→2), agg(1→1), log(1→0), fout(1→0)
- 플러그인 예시: jflat(JSON 평탄화), regex(정규식 추출)
- 카테고리: source / transform / sink

## 디자인 토큰
색상·간격·타이포는 원본 디자인 명세를 픽셀 단위로 재현했다. 색상 토큰은 [Palette.kt](composeApp/src/commonMain/kotlin/dataflow/Palette.kt)에 정의되어 있다.
- 배경: 앱 #14161B · 패널 #1B1E26 · 캔버스 #101218 · 노드 #1D212B
- 액센트: #5B8CFF · 성공 #34C98E · 에러 #FF5C5C · 카테고리 #22C3A6 / #B07BFF / #FF9D5C
- 격자 20 단위, 노드/카드 radius 7–9, 버튼 5–6
- 폰트는 시스템 산세리프(UI) + 모노스페이스(id·코드값) 사용

## 참고
격자 스냅·포트 배치·베지어·시뮬레이션 등 수치 명세는 원본 HTML 디자인 프로토타입에서 가져왔다. 이 저장소는 그 명세를 Compose Multiplatform으로 재구현한 결과물이다.
