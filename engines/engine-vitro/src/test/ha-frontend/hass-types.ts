// Trimmed for Vitro's island contract test (IslandHassContractTest): the interfaces Home Assistant's frontend hands a
// custom card as `hass`, and the public members of `hass.connection`. The interfaces are upstream's, unchanged;
// imports, other types and method bodies are left out. Not in any APK. Licence: Apache-2.0 (LICENSES/Apache-2.0.txt).
//
// From home-assistant/frontend src/types.ts at 421f8b19861cacc162da79a0459f522db2c1ec30 (2026-10-29),
// Copyright Home Assistant contributors, https://github.com/home-assistant/frontend
// and home-assistant/home-assistant-js-websocket lib/connection.ts at afcb55a4f76fa9855de52ea8fdb9717c314da267
// (2026-11-19), Copyright Paulus Schoutsen and contributors, https://github.com/home-assistant/home-assistant-js-websocket
//
// To update: copy the same interfaces from a newer frontend (and the Connection members), then run the test. A new
// member fails it until the island's `hass` has it or the test lists it as a known gap, with why.

export interface HomeAssistantRegistries {
  entities: Record<string, EntityRegistryDisplayEntry>;
  devices: Record<string, DeviceRegistryEntry>;
  areas: Record<string, AreaRegistryEntry>;
  floors: Record<string, FloorRegistryEntry>;
}

export interface HomeAssistantInternationalization {
  // i18n
  // current effective language in that order:
  //   - backend saved user selected language
  //   - language in local app storage
  //   - browser language
  //   - english (en)
  language: string;
  // local stored language, keep that name for backward compatibility
  selectedLanguage: string | null;
  locale: FrontendLocaleData;
  localize: LocalizeFunc;
  translationMetadata: TranslationMetadata;
  loadBackendTranslation(
    category: Parameters<typeof getHassTranslations>[2],
    integrations?: Parameters<typeof getHassTranslations>[3],
    configFlow?: Parameters<typeof getHassTranslations>[4]
  ): Promise<LocalizeFunc>;
  loadFragmentTranslation(fragment: string): Promise<LocalizeFunc | undefined>;
}

export interface HomeAssistantApi {
  callService<T = any>(
    domain: ServiceCallRequest["domain"],
    service: ServiceCallRequest["service"],
    serviceData?: ServiceCallRequest["serviceData"],
    target?: ServiceCallRequest["target"],
    notifyOnError?: boolean,
    returnResponse?: boolean
  ): Promise<ServiceCallResponse<T>>;
  callApi<T>(
    method: "GET" | "POST" | "PUT" | "DELETE",
    path: string,
    parameters?: Record<string, any>,
    headers?: Record<string, string>
  ): Promise<T>;
  callApiRaw( // introduced in 2024.11
    method: "GET" | "POST" | "PUT" | "DELETE",
    path: string,
    parameters?: Record<string, any>,
    headers?: Record<string, string>,
    signal?: AbortSignal
  ): Promise<Response>;
  fetchWithAuth(path: string, init?: Record<string, any>): Promise<Response>;
  sendWS(msg: MessageBase): void;
  callWS: CallWS;
}

export interface HomeAssistantFormatters {
  formatEntityState(stateObj: HassEntity, state?: string): string;
  formatEntityStateToParts(stateObj: HassEntity, state?: string): ValuePart[];
  formatEntityAttributeValue(
    stateObj: HassEntity,
    attribute: string,
    value?: any
  ): string;
  formatEntityAttributeValueToParts(
    stateObj: HassEntity,
    attribute: string,
    value?: any
  ): ValuePart[];
  formatEntityAttributeName(stateObj: HassEntity, attribute: string): string;
  formatEntityName(
    stateObj: HassEntity,
    name?: string | EntityNameItem | EntityNameItem[],
    options?: EntityNameOptions
  ): string;
}

export interface HomeAssistantConnection {
  connection: Connection;
  connected: boolean;
  debugConnection: boolean;
  hassUrl(path?): string;
}

export interface HomeAssistantUI {
  themes: Themes;
  selectedTheme: ThemeSettings | null;
  panels: Panels;
  panelUrl: string;
  dockedSidebar: "docked" | "always_hidden" | "auto";
  kioskMode: boolean;
  enableShortcuts: boolean;
  vibrate: boolean;
  suspendWhenHidden: boolean;
}

export interface HomeAssistantConfig {
  auth: Auth & { external?: ExternalMessaging };
  config: HassConfig & { logging?: HassLoggingConfig };
  user?: CurrentUser;
  userData?: CoreFrontendUserData;
  systemData?: CoreFrontendSystemData;
}

export interface HomeAssistant
  extends
    HomeAssistantRegistries,
    HomeAssistantInternationalization,
    HomeAssistantApi,
    HomeAssistantFormatters,
    HomeAssistantConnection,
    HomeAssistantUI,
    HomeAssistantConfig {
  states: HassEntities;
  services: HassServices;
}

// home-assistant-js-websocket lib/connection.ts: class Connection, its public members (signatures only).
export declare class Connection {
  haVersion: string;
  get connected(): boolean;
  addEventListener(eventType: Events, callback: ConnectionEventListener): void;
  removeEventListener(eventType: Events, callback: ConnectionEventListener): void;
  fireEvent(eventType: Events, eventData?: any): void;
  suspendReconnectUntil(suspendPromise: Promise<void>): void;
  suspend(): void;
  reconnect(force?: boolean): void;
  close(): void;
  subscribeEvents<EventType>(callback: (ev: EventType) => void, eventType?: string): Promise<SubscriptionUnsubscribe>;
  ping(): Promise<unknown>;
  sendMessage(message: MessageBase, commandId?: number): void;
  sendMessagePromise<Result>(message: MessageBase): Promise<Result>;
  subscribeMessage<Result>(callback: (result: Result) => void, subscribeMessage: MessageBase, options?: { resubscribe?: boolean; preCheck?: () => boolean | Promise<boolean> }): Promise<SubscriptionUnsubscribe>;
}
