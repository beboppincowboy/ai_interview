import { createRootRoute, createRoute, createRouter, lazyRouteComponent, Outlet, type RouterHistory } from "@tanstack/react-router";
import { AppShell } from "@/components/shell/AppShell";
import { DeletedState } from "@/components/library/DeletedState";
import { Toaster } from "@/components/ui/sonner";

const rootRoute = createRootRoute({
  component: () => (
    <>
      <AppShell><Outlet /></AppShell>
      <Toaster position="bottom-right" />
    </>
  ),
  // Unknown paths get the same page as links to deleted items.
  notFoundComponent: () => <DeletedState />
});

const routeTree = rootRoute.addChildren([
  createRoute({ getParentRoute: () => rootRoute, path: "/", component: lazyRouteComponent(() => import("@/pages/HomePage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/flow", component: lazyRouteComponent(() => import("@/pages/ResumePickerPage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/flow/$resumeId", component: lazyRouteComponent(() => import("@/pages/ScorePage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/flow/$resumeId/jobs", component: lazyRouteComponent(() => import("@/pages/TargetJobPickerPage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/flow/$resumeId/jobs/$jobId", component: lazyRouteComponent(() => import("@/pages/FitPage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/practice/$setId", component: lazyRouteComponent(() => import("@/pages/PracticePage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/voice/sessions/$sessionId", component: lazyRouteComponent(() => import("@/pages/VoiceSessionPage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/library/resumes", component: lazyRouteComponent(() => import("@/pages/ResumesPage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/library/jobs", component: lazyRouteComponent(() => import("@/pages/TargetJobsPage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/library/experiences", component: lazyRouteComponent(() => import("@/pages/ExperiencesPage")) }),
  createRoute({ getParentRoute: () => rootRoute, path: "/history", component: lazyRouteComponent(() => import("@/pages/HistoryPage")) })
]);

export function makeRouter(history?: RouterHistory) {
  // Each page is its own chunk; hovering or focusing a link starts loading it before the click.
  return createRouter({ routeTree, history, scrollRestoration: true, defaultPreload: "intent" });
}

export const router = makeRouter();

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
