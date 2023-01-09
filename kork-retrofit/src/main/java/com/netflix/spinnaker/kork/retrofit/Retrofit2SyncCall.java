/*
 * Copyright 2023 OpsMx, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.kork.retrofit;

import com.netflix.spinnaker.kork.retrofit.exceptions.RetrofitException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerNetworkException;
import java.io.IOException;
import retrofit2.Call;

public class Retrofit2SyncCall<T> {

  /*
   * This method used to handle IOException from {@link Call}.execute method centrally,
   * instead of handling exception in all places of retrofit2 API call
   * */
  public static <T> T execute(Call<T> call) {
    try {
      return call.execute().body();
    } catch (IOException e) {
      throw new SpinnakerNetworkException(RetrofitException.networkError(e));
    }
  }
}
