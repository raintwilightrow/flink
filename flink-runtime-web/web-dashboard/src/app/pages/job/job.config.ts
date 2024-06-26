/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { InjectionToken } from '@angular/core';

import { ModuleConfig } from '@flink-runtime-web/core/module-config';

export type JobModuleConfig = Pick<ModuleConfig, 'routerTabs'>;

export const JOB_MODULE_DEFAULT_CONFIG: Required<JobModuleConfig> = {
  routerTabs: [
    { title: 'Overview', path: 'overview' },
    { title: 'Exceptions', path: 'exceptions' },
    { title: 'Data Skew', path: 'dataskew' },
    { title: 'TimeLine', path: 'timeline' },
    { title: 'Checkpoints', path: 'checkpoints' },
    { title: 'Configuration', path: 'configuration' }
  ]
};

export const JOB_MODULE_CONFIG = new InjectionToken<JobModuleConfig>('job-module-config', {
  providedIn: 'root',
  factory: () => JOB_MODULE_DEFAULT_CONFIG
});
