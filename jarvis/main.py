"""
J.A.R.V.I.S. Linux Software Application Entry Point
Supports PySide6 Desktop GUI, System Tray, and Headless CLI Daemon mode.
"""

import sys
import os
import argparse
import asyncio
import logging

from jarvis.runtime.assistant_runtime import AssistantRuntime

logging.basicConfig(level=logging.INFO, format="[%(asctime)s] [%(name)s/%(levelname)s]: %(message)s")
logger = logging.getLogger("JarvisMain")

def main():
    parser = argparse.ArgumentParser(description="J.A.R.V.I.S. Autonomous Linux AI Super-Agent")
    parser.add_argument("--cli", type=str, help="Execute natural language command in headless CLI mode")
    parser.add_argument("--daemon", action="store_true", help="Run in headless systemd daemon mode")
    parser.add_argument("--overlay", action="store_true", help="Launch the transparent Arc Reactor HUD overlay")
    args = parser.parse_args()

    runtime = AssistantRuntime()
    
    # Initialize hardware hooks
    from jarvis.hardware.earbuds import EarbudHookInterceptor
    earbud_hook = EarbudHookInterceptor(runtime)

    # Headless CLI Mode
    if args.cli:
        async def run_cli():
            res = await runtime.execute_command(args.cli)
            print(f"\n🤖 J.A.R.V.I.S.: {res.get('spoken_response')}")
        asyncio.run(run_cli())
        return

    # Headless Daemon Mode
    if args.daemon:
        logger.info("Running J.A.R.V.I.S. in headless systemd daemon mode...")
        earbud_hook.start()
        runtime.subsystems.wake_engine.start_listening(
            callback=lambda: logger.info("Wake word detected in daemon mode")
        )
        try:
            asyncio.get_event_loop().run_forever()
        except KeyboardInterrupt:
            logger.info("Stopping J.A.R.V.I.S. daemon")
            earbud_hook.stop()
        return

    # PySide6 Stitch Material 3 Desktop HUD Mode
    from PySide6.QtWidgets import QApplication
    from qasync import QEventLoop
    from jarvis.ui.main_window import MainWindow

    app = QApplication(sys.argv)
    loop = QEventLoop(app)
    asyncio.set_event_loop(loop)

    # Launch main window
    window = MainWindow(runtime)
    window.show()
    
    overlay = None
    if args.overlay:
        from jarvis.ui.floating_overlay import JarvisFloatingOverlay
        overlay = JarvisFloatingOverlay(runtime)
        overlay.show()

    # Start background continuous audio wake-word listener and hardware hooks
    earbud_hook.start()
    runtime.subsystems.wake_engine.start_listening(
        callback=lambda: window.dashboard_tab._handle_mic_click()
    )

    with loop:
        loop.run_forever()

if __name__ == "__main__":
    main()
