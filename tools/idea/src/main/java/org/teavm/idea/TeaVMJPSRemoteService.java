/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Random;
import org.jetbrains.annotations.NotNull;
import org.teavm.idea.jps.remote.TeaVMBuilderAssistant;
import org.teavm.idea.jps.remote.TeaVMElementLocation;

public class TeaVMJPSRemoteService extends UnicastRemoteObject implements ApplicationComponent, TeaVMBuilderAssistant {
    private static final int MIN_PORT = 10000;
    private static final int MAX_PORT = 1 << 16;
    private ProjectManager projectManager = ProjectManager.getInstance();
    private int port;
    private Registry registry;

    public TeaVMJPSRemoteService() throws RemoteException {
        super();
    }

    @Override
    public void initComponent() {
        Random random = new Random();
        for (int i = 0; i < 20; ++i) {
            port = random.nextInt(MAX_PORT - MIN_PORT) + MIN_PORT;
            try {
                registry = LocateRegistry.createRegistry(port);
            } catch (RemoteException e) {
                continue;
            }
            try {
                registry.bind(TeaVMBuilderAssistant.ID, this);
            } catch (RemoteException | AlreadyBoundException e) {
                throw new IllegalStateException("Could not bind remote build assistant service", e);
            }
            return;
        }
        throw new IllegalStateException("Could not create RMI registry");
    }

    public int getPort() {
        return port;
    }

    @Override
    public void disposeComponent() {
        try {
            registry.unbind(TeaVMBuilderAssistant.ID);
            UnicastRemoteObject.unexportObject(registry, true);
        } catch (RemoteException | NotBoundException e) {
            throw new IllegalStateException("Could not clean-up RMI server", e);
        }
    }

    @NotNull
    @Override
    public String getComponentName() {
        return "TeaVM JPS service";
    }

    @Override
    public TeaVMElementLocation getMethodLocation(String className, String methodName, String methodDesc)
            throws RemoteException {
        TeaVMElementLocation[] resultHolder = new TeaVMElementLocation[1];

        ApplicationManager.getApplication().runReadAction(() -> {
            for (Project project : projectManager.getOpenProjects()) {
                JavaPsiFacade psi = JavaPsiFacade.getInstance(project);
                PsiClass cls = psi.findClass(className, GlobalSearchScope.allScope(project));
                if (cls == null) {
                    continue;
                }

                for (PsiMethod method : cls.getAllMethods()) {
                    if (!method.getName().equals(methodName)) {
                        continue;
                    }
                    // TODO: check method raw signature
                    resultHolder[0] = getMethodLocation(method);
                    return;
                }
            }
        });

        return resultHolder[0];
    }

    private TeaVMElementLocation getMethodLocation(PsiMethod method) {
        return new TeaVMElementLocation(method.getTextOffset(), method.getTextOffset() + method.getTextLength(),
                -1, -1, method.getContainingFile().getVirtualFile().getPath());
    }
}
