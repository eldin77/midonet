# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from midonetclient.api import MidonetApi
from topology.resource_base import ResourceBase
from topology.tunnel_zone_host import TunnelZoneHost

class TunnelZone(ResourceBase):

    def __init__(self,attrs):
        self.name = attrs['name']
        self.type = attrs['type']


    def add(self,api,tx,hosts):

        zone = None
        if self.type == 'gre':
            zone = api.add_gre_tunnel_zone().name(self.name).create()
            tx.append(zone)

        if zone:
            for host in hosts:
                tx.append(zone.add_tunnel_zone_host()\
                           .host_id(host.hostId)\
                           .ip_address(host.ipAddress).create())
