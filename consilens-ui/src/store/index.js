import { defineStore } from "pinia";

const THEME_KEY = "consilens-theme";

export const useAppStore = defineStore("app", {
  state: () => ({
    theme: localStorage.getItem(THEME_KEY) || "bright",
  }),
  actions: {
    updateTheme(theme) {
      this.theme = theme;
      localStorage.setItem(THEME_KEY, theme);
    },
    toggleTheme() {
      this.updateTheme(this.theme === "dark" ? "bright" : "dark");
    },
  },
});
