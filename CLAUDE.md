# DtcSimulator - In-Device Data Communication Filtering App

## 概要
DtcSimulatorは、Android VPN Service APIを活用した通信フィルタリングアプリケーションです。特定のmeta-dataタグを持つアプリケーションのみに通信を許可し、衛星データ通信の特性（高レイテンシー、低スループット、パケットロス）をシミュレートする機能を提供します。

## 主要機能

### Phase 1: 基本的な通信フィルタリング ✅ 実装済み
- AndroidManifestのmeta-dataタグに基づくアプリケーションフィルタリング
- 許可されたアプリケーションのみデータ通信を許可
- VPN Serviceを使用した透過的なパケットフィルタリング

### Phase 2: 衛星通信シミュレーション
- **レイテンシーシミュレーション** ✅ **実装済み**
    - 上り（Outbound）/下り（Inbound）独立設定
    - 指数スケールUI（0ms, 10ms, 100ms, 1000ms, 10000ms）
- **パケットロス再現** ✅ **実装済み**
    - パケットロス率の設定（0-100%）
    - ランダムパケットドロップ
- スループット制限 ⏳ 未実装
- `PacketDelayManager` によるパケットバッファリングと遅延送出

### Phase 3: ネットワークプロファイル管理 ✅ **実装済み**
- **マニュアル/プリセット切り替えUI**
    - マニュアルモード: スライダーで個別パラメータ設定
    - プリセットモード: YAML定義済みプロファイルから選択
- **YAMLベースのプロファイル定義**
    - SnakeYAML 2.2 による標準YAML解析
    - 複数プロファイルの保存・管理
    - デフォルトプロファイル: LEO/GEO Satellite, 3G Mobile, Edge Network

## アーキテクチャ設計

### 1. コンポーネント構成

```
DtcSimulator/
├── VpnService (通信制御の中核)
│   ├── DtcVpnService (サービスライフサイクル管理)
│   ├── ServerVpnConnection (外部サーバー経由接続)
│   └── LocalVpnConnection (ローカルスタック接続)
├── PacketDelayManager (遅延シミュレーション)
│   └── PriorityBlockingQueue によるタイムスタンプ管理
├── AllowlistManager (アプリケーション検出)
├── NetworkProfileManager (プロファイル管理)
│   ├── YAML解析 (SnakeYAML 2.2)
│   └── SharedPreferences永続化
├── UI Layer
│   ├── MainActivity (接続制御・パラメータ設定)
│   ├── SettingsActivity (サーバー設定・プロファイル編集)
│   └── AllowedAppsAdapter (アプリリスト表示)
└── Statistics
    └── VpnStats (トラフィック統計)
```

### 2. レイテンシーシミュレーション実装
UIスライダー（0-100%）からミリ秒への変換には以下の指数関数を使用しています：
- `0%` -> `0ms`
- `25%` -> `10ms`
- `50%` -> `100ms`
- `75%` -> `1000ms`
- `100%` -> `10000ms`

`PacketDelayManager` がパケットを `PriorityBlockingQueue` に保持し、設定された遅延時間が経過したパケットから順次送出します。

## 実装の優先順位

### Phase 1: MVP（最小実装）✅ 完了
1. ✅ AllowlistManagerの実装
2. ✅ 基本的なVPN Serviceの構築
3. ✅ meta-dataベースのフィルタリング
4. ✅ UIでの許可アプリ表示
5. ✅ Android 14/15対応

### Phase 2: ネットワークシミュレーション ✅ 完了
1. ✅ レイテンシーシミュレーション（上下別設定）
2. ✅ パケットロス実装
3. ⏳ スループット制限（UIのみ実装、実効制限は未実装）
4. ✅ 統計情報の収集と表示

### Phase 3: プロファイル管理 ✅ 完了
1. ✅ ネットワークプロファイルデータモデル定義
2. ✅ YAML形式でのプロファイル記述（SnakeYAML統合）
3. ✅ マニュアル/プリセット切り替えUI実装
4. ✅ プロファイル設定画面（SettingsActivity拡張）
5. ✅ デフォルトプロファイルの提供

## 開発環境
- **最小SDK**: API 21 (Android 5.0)
- **推奨SDK**: API 34+ (Android 14+)
- **言語**: Kotlin

## ビルドとデバッグ

### ビルド
```bash
./gradlew assembleDebug
```

### ログ監視
```bash
adb logcat | grep -E "DtcVpnService|ServerVpnConnection|LocalVpnConnection|PacketDelayManager"
```

---
**Last Updated**: 2025-12-26
**Version**: 0.4.0-alpha (Phase 3 Network Profiles with SnakeYAML)