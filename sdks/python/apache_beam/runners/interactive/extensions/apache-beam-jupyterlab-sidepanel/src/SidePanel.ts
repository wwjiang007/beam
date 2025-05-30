// Licensed under the Apache License, Version 2.0 (the 'License'); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

import {
  ReactWidget,
  SessionContext,
  ISessionContext,
  SessionContextDialogs
} from '@jupyterlab/apputils';
import { IRenderMimeRegistry } from '@jupyterlab/rendermime';
import { ServiceManager } from '@jupyterlab/services';
import { Message } from '@lumino/messaging';
import { BoxPanel } from '@lumino/widgets';

/**
 * The side panel: main user interface of the extension.
 *
 * Multiple instances of the side panel can be opened at the same time. They
 * can be operated independently but sharing the same kernel state if connected
 * to the same notebook session model or running kernel instance.
 */
export class SidePanel extends BoxPanel {
  constructor(
    manager: ServiceManager.IManager,
    rendermime: IRenderMimeRegistry,
    sessionContext: SessionContext,
    title: string,
    widget: ReactWidget
  ) {
    super({
      direction: 'top-to-bottom',
      alignment: 'end'
    });
    this.id = 'apache-beam-jupyterlab-sidepanel';
    this.title.label = title;
    this.title.closable = true;
    this._sessionContext = sessionContext;
    this._widget = widget;
    this.addWidget(this._widget);
    this.initializeSession(manager);
  }

  async initializeSession(manager: ServiceManager.IManager): Promise<void> {
    const sessionContext = await this._sessionContext.initialize();
    if (!sessionContext) {
      console.error('Cannot initialize the session in SidePanel.');
      return;
    }
    const sessionModelItr = manager.sessions.running();
    const firstModel = sessionModelItr.next();
    let onlyOneUniqueKernelExists = true;
    if (firstModel === undefined) {
      // There is zero unique running kernel.
      onlyOneUniqueKernelExists = false;
    } else {
      let sessionModel = sessionModelItr.next();
      while (sessionModel !== undefined) {
        if (sessionModel.value.kernel.id !== firstModel.value.kernel.id) {
          // There is more than one unique running kernel.
          onlyOneUniqueKernelExists = false;
          break;
        }
        sessionModel = sessionModelItr.next();
      }
    }
    try {
      // Create a new notebook session with the same model of the first
      // session (any session would suffice) when there is only one running
      // kernel.
      if (onlyOneUniqueKernelExists) {
        this._sessionContext.sessionManager.connectTo({
          model: firstModel.value,
          kernelConnectionOptions: {
            // Only one connection can handleComms. Leave it to the connection
            // established by the opened notebook.
            handleComms: false
          }
        });
        // Connect to the unique kernel.
        this._sessionContext.changeKernel(firstModel.value.kernel);
      } else {
        // Let the user choose among sessions and kernels when there is no
        // or more than 1 running kernels.
        const sessionContextDialogs = new SessionContextDialogs();
        await sessionContextDialogs.selectKernel(this._sessionContext);
      }
    } catch (err) {
      console.error(`Failed to initialize the session in SidePanel.\n${err}`);
    }
  }

  get session(): ISessionContext {
    return this._sessionContext;
  }

  dispose(): void {
    this._sessionContext.dispose();
    super.dispose();
  }

  protected onCloseRequest(msg: Message): void {
    super.onCloseRequest(msg);
    this.dispose();
  }

  private _widget: ReactWidget;
  private _sessionContext: SessionContext;
}
