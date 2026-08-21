/* eslint-disable no-console */

import { register } from "register-service-worker";

let refreshing = false;

function handleControllerChange() {
  if (refreshing) {
    return;
  }
  refreshing = true;
  window.location.reload();
}

function skipWaiting(registration) {
  const worker = registration && registration.waiting;
  if (worker) {
    worker.postMessage({ type: "SKIP_WAITING" });
  }
}

export function registerServiceWorker() {
  try {
    if (
      process.env.NODE_ENV === "production" &&
      !window.getQueryString("nopwa")
    ) {
      if ("serviceWorker" in navigator) {
        navigator.serviceWorker.addEventListener(
          "controllerchange",
          handleControllerChange
        );
      }
      register(`${process.env.BASE_URL}service-worker.js`, {
        ready() {
          window.serviceWorkerReady = true;
        },
        registered(registration) {
          if (window.localStorage) {
            const currentVersion = window.localStorage.getItem(
              "READER_APP_BUILD_VERSION"
            );
            const newVersion = process.env.VUE_APP_BUILD_VERSION;
            if (currentVersion !== newVersion) {
              skipWaiting(registration);
              window.localStorage.setItem(
                "READER_APP_BUILD_VERSION",
                newVersion
              );
            }
          }
        },
        updatefound(registration) {
          const worker = registration.waiting || registration.installing;
          if (!worker) {
            return;
          }
          const activate = () => {
            if (
              worker.state === "installed" &&
              navigator.serviceWorker.controller
            ) {
              worker.postMessage({ type: "SKIP_WAITING" });
            }
          };
          if (worker.state === "installed") {
            activate();
          } else {
            worker.addEventListener("statechange", activate);
          }
        }
      });
    }
  } catch (error) {
    //
  }
}