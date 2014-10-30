/*
 * Copyright 2014 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ICONFIGURATIONTRANSPORT_HPP_
#define ICONFIGURATIONTRANSPORT_HPP_

#include "kaa/gen/EndpointGen.hpp"
#include <boost/shared_ptr.hpp>

namespace kaa {

/**
 * Updates the Configuration manager state.
 */
class IConfigurationTrasnport {
public:

    /**
     * Creates the configuration request.
     *
     * @return the configuration request object.
     * @see ConfigurationSyncRequest
     */
    virtual boost::shared_ptr<ConfigurationSyncRequest> createConfigurationRequest() = 0;

    /**
     * Updates the state of the Configuration manager according to the given response.
     *
     * @param response the configuration response.
     * @see ConfigurationSyncResponse
     */
    virtual void onConfigurationResponse(const ConfigurationSyncResponse &response) = 0;

    virtual ~IConfigurationTrasnport() {}
};

}  // namespace kaa


#endif /* ICONFIGURATIONTRANSPORT_HPP_ */