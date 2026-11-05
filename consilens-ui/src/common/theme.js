import { useAppStore } from "@/store";

/**
 * 设计令牌唯一来源：Reafuture 风格（靛蓝主色 #3B77F1 + 冷调中性色）。
 * 层级靠 1px 描边/底色分层建立，不用阴影；颜色变更只改这里与 global.scss 的 :root 变量。
 * 亮色：浅灰外壳 + 纯白面板；暗色：板岩外壳 #202A2C + 深蓝黑面板 #0F161C。
 */

// primary.base 同时驱动 naive 实底按钮（白字压色），12px 小字需 >=4.5:1，
// 故比 CSS --primary 深一档；深色主题同理（暗色下 naive 主按钮仍是白字）。
const palette = {
  primary: { base: "#3570E0", hover: "#2F63D6", pressed: "#2A55B8" },
  success: { base: "#1B8A4E", hover: "#167A44", pressed: "#12683A" },
  warning: { base: "#A87C1F", hover: "#946C18", pressed: "#7E5C13" },
  error: { base: "#C63F2C", hover: "#AE3625", pressed: "#952E1F" },
};

const darkPalette = {
  primary: { base: "#2F63D6", hover: "#3D74E8", pressed: "#4A82F0" },
  success: { base: "#34A853", hover: "#3FBB60", pressed: "#4CC96E" },
  warning: { base: "#D9A93B", hover: "#E2B855", pressed: "#EAC56F" },
  error: { base: "#E8705C", hover: "#EF8574", pressed: "#F49A8C" },
};

// --accent-amber：亮 / 暗主题同值（doris、趋势图差异线等使用）
const accentAmber = "#D9A93B";

function tone(name, t) {
  return {
    [`${name}Color`]: t.base,
    [`${name}ColorHover`]: t.hover,
    [`${name}ColorPressed`]: t.pressed,
    [`${name}ColorSuppl`]: t.base,
  };
}

function semanticTones(p) {
  return {
    ...tone("primary", p.primary),
    ...tone("info", p.primary),
    ...tone("success", p.success),
    ...tone("warning", p.warning),
    ...tone("error", p.error),
  };
}

// 软标签（StateTag 用 bordered=false）：浅底 + 同色文字
function tagTones(variant) {
  const isDark = variant === "dark";
  const soft = (hex, alpha) => {
    const n = parseInt(hex.slice(1), 16);
    const r = (n >> 16) & 255;
    const g = (n >> 8) & 255;
    const b = n & 255;
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  };
  return {
    Tag: {
      borderRadius: "6px",
      fontWeight: "500",
      heightSmall: "20px",
      heightMedium: "22px",
      fontSizeTiny: "11px",
      fontSizeSmall: "11px",
      fontSizeMedium: "12px",
      border: "1px solid transparent",
      // 默认（中性）徽章
      color: isDark ? "#2B2D30" : "#EDEEF0",
      textColor: isDark ? "#BCBEC4" : "#4A5058",
      // 主色徽章：靛蓝底 + 雾紫蓝字
      colorPrimary: isDark ? "#22305C" : "#E3EDF7",
      textColorPrimary: isDark ? "#9AA9E6" : "#2F63D6",
      colorInfo: isDark ? "#22305C" : "#E3EDF7",
      textColorInfo: isDark ? "#9AA9E6" : "#2F63D6",
      colorSuccess: soft(isDark ? "#34A853" : "#22A45D", isDark ? 0.16 : 0.13),
      textColorSuccess: isDark ? "#6FD79A" : "#17784A",
      colorWarning: soft(isDark ? "#D9A93B" : "#D9A93B", isDark ? 0.14 : 0.16),
      textColorWarning: isDark ? "#E8C77A" : "#7E5C13",
      colorError: soft("#E8543F", isDark ? 0.18 : 0.12),
      textColorError: isDark ? "#F5A192" : "#AE3625",
    },
  };
}

const fontFamily =
  'Inter, "Geist", -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif';

const naiveThemeLight = {
  common: {
    ...semanticTones(palette),
    fontFamily,
    borderRadius: "8px",
    borderColor: "#E0E3E8",
    dividerColor: "#ECEDEF",
    fontWeightStrong: "600",
    fontSize: "13px",
    fontSizeMedium: "13px",
    fontSizeSmall: "12px",
    fontSizeTiny: "11px",
    textColor1: "#1F2329",
    textColor2: "#5A6169",
    // 与 --text-muted 同源：12px 说明文字需满足 WCAG AA 4.5:1
    textColor3: "#6B7178",
    textColorDisabled: "#B9BDC2",
    placeholderColor: "#6B7178",
    placeholderColorDisabled: "#9AA0A6",
    bodyColor: "#F3F4F6",
    cardColor: "#FFFFFF",
    modalColor: "#FFFFFF",
    popoverColor: "#FFFFFF",
    tableColor: "#FFFFFF",
    hoverColor: "rgba(15, 22, 28, 0.04)",
    primaryColorFocus: "rgba(59, 119, 241, 0.22)",
    errorColorFocus: "rgba(198, 63, 44, 0.2)",
    caretColor: "#3B77F1",
    scrollbarColor: "rgba(0, 0, 0, 0.18)",
    scrollbarColorHover: "rgba(0, 0, 0, 0.28)",
  },
  ...tagTones("light"),
  Card: {
    borderRadius: "10px",
    titleFontSizeSmall: "15px",
    titleFontWeight: "600",
    paddingSmall: "12px 16px",
    borderColor: "#E0E3E8",
    color: "#FFFFFF",
  },
  Button: {
    borderRadiusMedium: "8px",
    borderRadiusSmall: "7px",
    fontWeight: "500",
    sizeSmall: "28px",
    sizeMedium: "32px",
    paddingMedium: "0 14px",
    paddingSmall: "0 12px",
  },
  Input: {
    borderRadius: "8px",
    colorFocus: "#FFFFFF",
    borderFocus: "1px solid #3B77F1",
    boxShadowFocus: "0 0 0 2px rgba(59, 119, 241, 0.14)",
  },
  Select: {
    peers: {
      InternalSelection: {
        borderRadius: "8px",
        borderFocus: "1px solid #3B77F1",
        boxShadowFocus: "0 0 0 2px rgba(59, 119, 241, 0.14)",
      },
      InternalSelectMenu: { borderRadius: "8px" },
    },
  },
  DataTable: {
    thColor: "#F7F8FA",
    thColorHover: "#F1F3F6",
    tdColorHover: "#F5F7FB",
    thTextColor: "#1F2329",
    thFontWeight: "600",
    fontSizeMedium: "13px",
    fontSizeSmall: "13px",
    tdPaddingSmall: "8px 10px",
    thPaddingSmall: "8px 10px",
    lineHeight: "20px",
    borderColor: "#E9E9E9",
    borderRadius: "10px",
  },
  Menu: {
    fontSize: "13px",
    itemHeight: "34px",
    borderRadius: "8px",
    itemTextColor: "#5A6169",
    itemTextColorHover: "#1F2329",
    itemTextColorActive: "#1F2329",
    itemTextColorActiveHover: "#1F2329",
    itemIconColor: "#5A6169",
    itemIconColorHover: "#1F2329",
    groupTextColor: "#8A9099",
  },
  Tabs: {
    tabFontSizeMedium: "13px",
    tabFontWeightActive: "600",
    tabGapMediumLine: "20px",
    panePaddingTopMedium: "12px",
    tabTextColorLine: "#5A6169",
    tabTextColorHoverLine: "#1F2329",
    tabTextColorActiveLine: "#3F6FE0",
    barColor: "#3B77F1",
  },
  Breadcrumb: { fontSize: "12.5px", itemTextColor: "#8A9099" },
  Empty: { fontSize: "12.5px" },
  Form: { labelFontSizeTopSmall: "12px", labelHeightSmall: "20px", labelFontSizeLeftSmall: "12px" },
  Popover: { borderRadius: "10px", color: "#FFFFFF" },
  Tooltip: { borderRadius: "6px", fontSize: "12px" },
  Switch: { railBorderRadiusSmall: "8px" },
  Drawer: { borderRadius: "12px 0 0 12px" },
  Dialog: { borderRadius: "12px" },
  LoadingBar: { colorLoading: "#3B77F1" },
  Progress: { railColor: "#E0E3E8" },
  Alert: { borderRadius: "8px", padding: "10px 14px" },
  Descriptions: {
    thColor: "#F7F8FA",
    thTextColor: "#5A6169",
    tdTextColor: "#1F2329",
    borderColor: "#E9E9E9",
    thFontWeight: "600",
    borderRadius: "10px",
  },
  Steps: {
    stepDescriptionTextColorProcess: "#8A9099",
    indicatorColorProcess: "#3B77F1",
    indicatorTextColorProcess: "#FFFFFF",
    indicatorBorderColorProcess: "#3B77F1",
  },
};

// 暗色 overrides：darkTheme 之上反转文字/边框/表格，品牌语义色换亮一档
const naiveThemeDark = {
  common: {
    ...semanticTones(darkPalette),
    fontFamily,
    borderRadius: "8px",
    borderColor: "#393B40",
    dividerColor: "#2B2D30",
    fontWeightStrong: "600",
    fontSize: "13px",
    fontSizeMedium: "13px",
    fontSizeSmall: "12px",
    fontSizeTiny: "11px",
    textColor1: "#DFE1E5",
    textColor2: "#BCBEC4",
    textColor3: "#9EA0A8",
    textColorDisabled: "#6B6E73",
    placeholderColor: "#9EA0A8",
    placeholderColorDisabled: "#7C858C",
    bodyColor: "#2B2D30",
    cardColor: "#1E1F22",
    modalColor: "#2B2D30",
    popoverColor: "#2B2D30",
    tableColor: "#1E1F22",
    hoverColor: "rgba(255, 255, 255, 0.05)",
    primaryColorFocus: "rgba(59, 119, 241, 0.4)",
    errorColorFocus: "rgba(232, 112, 92, 0.35)",
    caretColor: "#5A8DF5",
    inputColor: "#2B2D30",
    inputColorDisabled: "#26282C",
    scrollbarColor: "rgba(255, 255, 255, 0.16)",
    scrollbarColorHover: "rgba(255, 255, 255, 0.28)",
  },
  ...tagTones("dark"),
  Card: {
    borderRadius: "10px",
    titleFontSizeSmall: "15px",
    titleFontWeight: "600",
    paddingSmall: "12px 16px",
    borderColor: "#393B40",
    color: "#1E1F22",
  },
  Button: {
    borderRadiusMedium: "8px",
    borderRadiusSmall: "7px",
    fontWeight: "500",
    paddingMedium: "0 14px",
    paddingSmall: "0 12px",
    // naive 暗色主题下 baseColor 为黑色，实心主按钮文字会跟着变黑，这里显式指定白字
    textColorPrimary: "#FFFFFF",
    textColorPrimaryHover: "#FFFFFF",
    textColorPrimaryPressed: "#FFFFFF",
  },
  Input: {
    borderRadius: "8px",
    color: "#2B2D30",
    colorFocus: "#2B2D30",
    border: "1px solid #393B40",
    borderHover: "1px solid #4A4D52",
    borderFocus: "1px solid #3B77F1",
    boxShadowFocus: "0 0 0 2px rgba(59, 119, 241, 0.3)",
    caretColor: "#5A8DF5",
  },
  Select: {
    peers: {
      InternalSelection: {
        borderRadius: "8px",
        color: "#2B2D30",
        colorActive: "#2B2D30",
        border: "1px solid #393B40",
        borderHover: "1px solid #4A4D52",
        borderActive: "1px solid #393B40",
        borderFocus: "1px solid #3B77F1",
        boxShadowFocus: "0 0 0 2px rgba(59, 119, 241, 0.3)",
        textColor: "#DFE1E5",
        placeholderColor: "#9EA0A8",
      },
      InternalSelectMenu: { borderRadius: "8px" },
    },
  },
  DataTable: {
    thColor: "#2B2D30",
    thColorHover: "#333639",
    tdColorHover: "#2B2D30",
    thTextColor: "#DFE1E5",
    thFontWeight: "600",
    fontSizeMedium: "13px",
    fontSizeSmall: "13px",
    tdPaddingSmall: "8px 10px",
    thPaddingSmall: "8px 10px",
    lineHeight: "20px",
    borderColor: "#2B2D30",
    borderRadius: "10px",
  },
  Menu: {
    fontSize: "13px",
    itemHeight: "36px",
    borderRadius: "8px",
    itemTextColor: "#C0C7CD",
    itemTextColorHover: "#E6EDF3",
    itemTextColorActive: "#E6EDF3",
    itemTextColorActiveHover: "#E6EDF3",
    itemIconColor: "#A0A5AB",
    itemIconColorHover: "#E6EDF3",
    groupTextColor: "#A0A5AB",
  },
  Pagination: {
    itemTextColor: "#BCBEC4",
    itemTextColorHover: "#DFE1E5",
    itemTextColorActive: "#DFE1E5",
    itemColorActive: "#393B40",
    itemColorActiveHover: "#45484D",
    itemColorHover: "#2B2D30",
    itemBorder: "1px solid transparent",
    itemBorderActive: "1px solid #393B40",
    itemBorderHover: "1px solid #393B40",
    itemBorderRadius: "6px",
  },
  Tabs: {
    tabFontSizeMedium: "13px",
    tabFontWeightActive: "600",
    tabGapMediumLine: "20px",
    panePaddingTopMedium: "12px",
    tabTextColorLine: "#A0A5AB",
    tabTextColorHoverLine: "#E6EDF3",
    tabTextColorActiveLine: "#7C8FD8",
    barColor: "#5B6FE0",
  },
  Breadcrumb: { fontSize: "12.5px", itemTextColor: "#A0A5AB" },
  Empty: { fontSize: "12.5px" },
  Popover: { borderRadius: "10px", color: "#2B2D30" },
  Tooltip: { borderRadius: "6px", fontSize: "12px" },
  Drawer: { borderRadius: "12px 0 0 12px" },
  Dialog: { borderRadius: "12px" },
  LoadingBar: { colorLoading: "#5A8DF5" },
  Progress: { railColor: "#393B40" },
  Alert: { borderRadius: "8px", padding: "10px 14px" },
  Descriptions: {
    thColor: "#2B2D30",
    thTextColor: "#9EA0A8",
    tdTextColor: "#BCBEC4",
    borderColor: "#2B2D30",
    thFontWeight: "600",
    borderRadius: "10px",
  },
  Steps: {
    stepDescriptionTextColorProcess: "#A0A5AB",
    indicatorColorProcess: "#3B77F1",
    indicatorTextColorProcess: "#FFFFFF",
    indicatorBorderColorProcess: "#3B77F1",
  },
};

// ===== ECharts 图表色值 =====
// ECharts 是 canvas 渲染，解析不了 CSS var()：按主题给出与 global.scss 变量同值的具体色值，
// 集中在这里声明，页面不得再硬编码 hex。
const chartPalette = {
  bright: {
    text: naiveThemeLight.common.textColor3, // --text-muted
    axisLine: naiveThemeLight.common.borderColor, // --border
    splitLine: naiveThemeLight.DataTable.borderColor, // --row-border
    card: naiveThemeLight.common.cardColor, // --card
    primary: palette.primary.base, // --primary
    amber: accentAmber,
    danger: palette.error.base, // --danger
  },
  dark: {
    text: naiveThemeDark.common.textColor3, // --text-muted
    axisLine: naiveThemeDark.common.borderColor, // --border
    splitLine: naiveThemeDark.DataTable.borderColor, // --row-border
    card: naiveThemeDark.common.cardColor, // --card
    primary: darkPalette.primary.base, // --primary
    amber: accentAmber,
    danger: darkPalette.error.base, // --danger
  },
};

// 按主题取图表色值（theme 为 store 的 "bright" | "dark"）
export function chartColors(theme) {
  return theme === "dark" ? chartPalette.dark : chartPalette.bright;
}

export function naiveTheme() {
  const store = useAppStore();
  return store.theme === "dark" ? naiveThemeDark : naiveThemeLight;
}
