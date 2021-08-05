import os
import re
import shutil
import zipfile
import requests
import json
from shutil import copy2
from urllib.request import urlretrieve, urlopen

# Input parameters
version_param = os.environ.get('RELEASE_VERSION')
is_latest_param = True if version_param == "master" else False
custom_m2repo_path = os.environ.get('M2_REPO')

# build constants
m2repo_path = custom_m2repo_path if custom_m2repo_path is not None else '/m2repo'
tmp_path = './tmp/%s' % version_param
policies_path = "%s/policies" % tmp_path
resources_path = "%s/resources" % tmp_path
fetchers_path = "%s/fetchers" % tmp_path
tracers_path = "%s/tracers" % tmp_path
services_path = "%s/services" % tmp_path
reporters_path = "%s/reporters" % tmp_path
repositories_path = "%s/repositories" % tmp_path
connectors_path = "%s/connectors" % tmp_path
snapshotPattern = re.compile('.*-SNAPSHOT')


def clean():
    if os.path.exists(tmp_path):
        shutil.rmtree(tmp_path)
    os.makedirs(tmp_path, exist_ok=True)
    os.makedirs(policies_path, exist_ok=True)
    os.makedirs(fetchers_path, exist_ok=True)
    os.makedirs(tracers_path, exist_ok=True)
    os.makedirs(resources_path, exist_ok=True)
    os.makedirs(services_path, exist_ok=True)
    os.makedirs(reporters_path, exist_ok=True)
    os.makedirs(repositories_path, exist_ok=True)
    os.makedirs(connectors_path, exist_ok=True)


def get_policies(release_json):
    components = release_json['components']
    search_pattern = re.compile('gravitee-policy-.*')
    policies = []
    for component in components:
        if search_pattern.match(component['name']) and 'gravitee-policy-api' != component['name']:
            policies.append(component)
            if "gravitee-policy-ratelimit" == component['name']:
                policies.append({"name": "gravitee-policy-quota", "version": component['version']})
                if int(component['version'].replace(".", "").replace("-SNAPSHOT", "")) >= 1100:
                    policies.append({"name": "gravitee-policy-spikearrest", "version": component['version']})
    return policies


def get_resources(release_json):
    components_name = [
        "gravitee-resource-cache",
        "gravitee-resource-oauth2-provider-generic",
        "gravitee-resource-oauth2-provider-am"
    ]
    resources = []
    for component_name in components_name:
        resources.append(get_component_by_name(release_json, component_name))
    return resources


def get_fetchers(release_json):
    components = release_json['components']
    search_pattern = re.compile('gravitee-fetcher-.*')
    fetchers = []
    for component in components:
        if search_pattern.match(component['name']) and 'gravitee-fetcher-api' != component['name']:
            fetchers.append(component)
    return fetchers


def get_tracers(release_json):
    components = release_json['components']
    search_pattern = re.compile('gravitee-tracer-.*')
    tracers = []
    for component in components:
        if search_pattern.match(component['name']):
            tracers.append(component)
    return tracers


def get_reporters(release_json):
    components_name = [
        "gravitee-reporter-file",
        "gravitee-reporter-tcp",
        "gravitee-elasticsearch"
    ]
    reporters = []
    for component_name in components_name:
        reporters.append(get_component_by_name(release_json, component_name))
    return reporters


def get_repositories(release_json):
    apim_version = get_component_by_name(release_json, 'gravitee-api-management')['version'];
    elastic_version = get_component_by_name(release_json, 'gravitee-elasticsearch')['version'];
    repositories = [
        {
            "artifact_id": "gravitee-apim-repository-mongodb",
            "group_id": "io.gravitee.apim.repository",
            "version": apim_version
        },
        {
            "artifact_id": "gravitee-apim-repository-jdbc",
            "group_id": "io.gravitee.apim.repository",
            "version": apim_version
        },
        {
            "artifact_id": "gravitee-apim-repository-hazelcast",
            "group_id": "io.gravitee.apim.repository",
            "version": apim_version
        },
        {
            "artifact_id": "gravitee-apim-repository-redis",
            "group_id": "io.gravitee.apim.repository",
            "version": apim_version
        },
        {
            "artifact_id": "gravitee-apim-repository-gateway-bridge-http-client",
            "group_id": "io.gravitee.apim.repository.gateway.bridge.http",
            "version": apim_version
        },
        {
            "artifact_id": "gravitee-apim-repository-gateway-bridge-http-server",
            "group_id": "io.gravitee.apim.repository.gateway.bridge.http",
            "version": apim_version
        },
        {
            "artifact_id": "gravitee-repository-elasticsearch",
            "group_id": "io.gravitee.repository",
            "version": elastic_version
        }
    ]
    return repositories


def get_services(release_json):
    components_name = [
        "gravitee-service-discovery-consul"
    ]
    components = release_json['components']
    search_pattern = re.compile('gravitee-policy-ratelimit')
    services = []
    for component in components:
        if search_pattern.match(component['name']):
            service = component.copy()
            service['name'] = 'gravitee-gateway-services-ratelimit'
            services.append(service)
            break

    for component_name in components_name:
        services.append(get_component_by_name(release_json, component_name))

    return services


def get_connectors(release_json):
    components = release_json['components']
    search_pattern = re.compile('gravitee-.*-connectors-ws')
    connectors = []
    for component in components:
        if search_pattern.match(component['name']):
            connectors.append(component)
    return connectors


def get_component_by_name(release_json, component_name):
    components = release_json['components']
    search_pattern = re.compile(component_name)
    for component in components:
        if search_pattern.match(component['name']):
            return component


def get_download_url(group_id, artifact_id, version, t):
    print('\n[get_download_url(group_id, artifact_id, version, t)]')
    print('\n[get_download_url(group_id, artifact_id, version, t)] group_id=%s\nartifact_id=%s\nartifact_id=%s\nt=%s' % (
        group_id, artifact_id, version, t))
    m2path = "%s/%s/%s/%s/%s-%s.%s" % (m2repo_path, group_id.replace(".", "/"), artifact_id, version, artifact_id, version, t)
    if os.path.exists(m2path):
        return m2path
    else:
        sonatypeUrl = "https://oss.sonatype.org/service/local/artifact/maven/redirect?r=%s&g=%s&a=%s&v=%s&e=%s" % (
            ("snapshots" if snapshotPattern.match(version) else "releases"), group_id.replace(".", "/"), artifact_id, version, t)
        f = urlopen(sonatypeUrl)
        return f.geturl()


def get_suffix_path_by_name(name):
    if name == "gravitee-portal-webui" or name == "gravitee-management-webui" or name == "gravitee-gateway" or name == "gravitee-management-rest-api":
        return ""

    if name.find("policy") == -1:
        suffix = name[name.find('-') + 1:name.find('-', name.find('-') + 1)]
        # if suffix == apim, it means that we use new APIM Monorepo
        if suffix == "apim":
            suffix = name[
                     name.find('-', name.find('-', name.find('-') + 1)) + 1:
                     name.find('-', name.find('-', name.find('-', name.find('-') + 1)) + 1)
                     ]
        if suffix == "gateway":
            return "/services"
        if suffix == "repository":
            return "/repositories"
        if suffix == "cockpit":
            return "/connectors"
        return "/" + suffix + "s"
    else:
        return "/policies"


def download(name, filename_path, url):
    print('\nDowloading %s\n%s' % (name, url))
    if url.startswith("http"):
        filename_path = tmp_path + get_suffix_path_by_name(name) + url[url.rfind('/'):]
        urlretrieve(url, filename_path)
    else:
        copy2(url, filename_path)

    print('\nDowloaded in %s' % filename_path)
    return filename_path


def unzip(files):
    unzip_dirs = []
    dist_dir = get_dist_dir_name()
    for file in files:
        with zipfile.ZipFile(file) as zip_file:
            zip_file.extractall("%s/%s" % (tmp_path, dist_dir))
            unzip_dir = "%s/%s/%s" % (tmp_path, dist_dir, sorted(zip_file.namelist())[0])
            unzip_dirs.append(unzip_dir)
            preserve_permissions(unzip_dir)
    return sorted(unzip_dirs)


def preserve_permissions(d):
    search_bin_pattern = re.compile(".*/bin$")
    search_gravitee_pattern = re.compile("gravitee(\.bat)?")
    perm = 0o0755
    for dirname, subdirs, files in os.walk(d):
        if search_bin_pattern.match(dirname):
            for file in files:
                if search_gravitee_pattern.match(file):
                    file_path = "%s/%s" % (dirname, file)
                    print("       set permission %o to %s" % (perm, file_path))
                    os.chmod(file_path, perm)


def copy_files_into(src_dir, dest_dir, exclude_pattern=None):
    if exclude_pattern is None:
        exclude_pattern = []
    filenames = [os.path.join(src_dir, fn) for fn in next(os.walk(src_dir))[2]]

    print("        copy")
    print("            %s" % filenames)
    print("        into")
    print("            %s" % dest_dir)
    for file in filenames:
        to_exclude = False
        for pattern in exclude_pattern:
            search_pattern = re.compile(pattern)
            if search_pattern.match(file):
                to_exclude = True
                break
        if to_exclude:
            print("[INFO] %s is excluded from files." % file)
            continue
        copy2(file, dest_dir)


def download_policies(policies):
    paths = []
    for policy in policies:
        if policy['name'] != "gravitee-policy-core":
            var_policy_name = policy['name']
            var_policy_version = policy['version']
            print('\nCalling get_download_url')
            print('\nCalling get_download_url for policy_name=%s\npolicy_version=%s' % (var_policy_name, var_policy_version))
            url = get_download_url("io.gravitee.policy", policy['name'], policy['version'], "zip")
            paths.append(
                download(policy['name'], '%s/%s-%s.zip' % (policies_path, policy['name'], policy['version']), url))
    return paths


def download_management_api(mgmt_api, default_version):
    v = default_version if 'version' not in mgmt_api else mgmt_api['version']
    url = get_download_url("io.gravitee.management.standalone", "gravitee-management-api-standalone-distribution-zip",
                           v, "zip")
    return download(mgmt_api['name'], '%s/%s-%s.zip' % (tmp_path, mgmt_api['name'], v), url)


def download_managementV3_api(mgmt_api, default_version):
    v = default_version if 'version' not in mgmt_api else mgmt_api['version']
    url = get_download_url("io.gravitee.apim.management.api.standalone.distribution",
                           "gravitee-apim-management-api-standalone-distribution-zip", v, "zip")
    return download("gravitee-management-rest-api", '%s/%s-%s.zip' % (tmp_path, "gravitee-management-rest-api", v), url)


def download_gateway(gateway, default_version):
    v = default_version if 'version' not in gateway else gateway['version']
    url = get_download_url("io.gravitee.gateway.standalone", "gravitee-gateway-standalone-distribution-zip",
                           v, "zip")
    return download(gateway['name'], '%s/%s-%s.zip' % (tmp_path, gateway['name'], v), url)


def download_fetchers(fetchers):
    paths = []
    for fetcher in fetchers:
        url = get_download_url("io.gravitee.fetcher", fetcher['name'], fetcher['version'], "zip")
        paths.append(
            download(fetcher['name'], '%s/%s-%s.zip' % (fetchers_path, fetcher['name'], fetcher['version']), url))
    return paths


def download_tracers(tracers):
    paths = []
    for tracer in tracers:
        url = get_download_url("io.gravitee.tracer", tracer['name'], tracer['version'], "zip")
        paths.append(
            download(tracer['name'], '%s/%s-%s.zip' % (tracers_path, tracer['name'], tracer['version']), url))
    return paths


def download_resources(resources):
    paths = []
    for resource in resources:
        url = get_download_url("io.gravitee.resource", resource['name'], resource['version'], "zip")
        paths.append(
            download(resource['name'], '%s/%s-%s.zip' % (resources_path, resource['name'], resource['version']), url))
    return paths


def download_services(services):
    paths = []
    for service in services:
        # for release < 1.22
        if service is not None:
            if service['name'] == "gravitee-gateway-services-ratelimit":
                url = get_download_url("io.gravitee.policy", service['name'], service['version'], "zip")
            else:
                url = get_download_url("io.gravitee.discovery", service['name'], service['version'], "zip")
            paths.append(
                download(service['name'], '%s/%s-%s.zip' % (services_path, service['name'], service['version']), url))
    return paths


def download_connectors(connectors):
    paths = []
    for connector in connectors:
        url = get_download_url("io.gravitee.cockpit", connector['name'], connector['version'], "zip")
        paths.append(
            download(connector['name'], '%s/%s-%s.zip' % (resources_path, connector['name'], connector['version']), url))
    return paths


def download_ui(ui, default_version):
    v = default_version if 'version' not in ui else ui['version']
    url = get_download_url("io.gravitee.management", ui['name'], v, "zip")
    return download(ui['name'], '%s/%s-%s.zip' % (tmp_path, ui['name'], v), url)


def download_portal_ui(ui, default_version):
    v = default_version if 'version' not in ui else ui['version']
    url = get_download_url("io.gravitee.portal", ui['name'], v, "zip")
    return download(ui['name'], '%s/%s-%s.zip' % (tmp_path, ui['name'], v), url)


def download_reporters(reporters):
    paths = []
    for reporter in reporters:
        name = "gravitee-reporter-elasticsearch" if "gravitee-elasticsearch" == reporter['name'] else reporter['name']

        url = get_download_url("io.gravitee.reporter", name, reporter['version'], "zip")
        paths.append(
            download(name, '%s/%s-%s.zip' % (reporters_path, name, reporter['version']), url))
    return paths


def download_repositories(repositories):
    paths = []
    for repository in repositories:
        artifact_id = repository['artifact_id']
        group_id = repository['group_id']
        name = repository['artifact_id']
        url = get_download_url(group_id, artifact_id, repository['version'], "zip")
        paths.append(download(name, '%s/%s-%s.zip' % (repositories_path, name, repository['version']), url))

    return paths


def prepare_gateway_bundle(gateway):
    print("==================================")
    print("Prepare %s" % gateway)
    bundle_path = unzip([gateway])[0]
    print("        bundle_path: %s" % bundle_path)
    copy_files_into(policies_path, bundle_path + "plugins")
    copy_files_into(resources_path, bundle_path + "plugins")
    copy_files_into(repositories_path, bundle_path + "plugins",
                    [".*gravitee-repository-elasticsearch.*", ".*gravitee-apim-repository-hazelcast.*",
                     ".*gravitee-apim-repository-redis.*"])
    copy_files_into(reporters_path, bundle_path + "plugins")
    copy_files_into(services_path, bundle_path + "plugins")
    copy_files_into(connectors_path, bundle_path + "plugins")
    print("makedirs: %s" % (bundle_path + "plugins/ext/repository-jdbc"))
    os.makedirs(bundle_path + "plugins/ext/repository-jdbc", exist_ok=True)


def prepare_ui_bundle(ui):
    print("==================================")
    print("Prepare %s" % ui)
    bundle_path = unzip([ui])[0]
    print("        bundle_path: %s" % bundle_path)


def prepare_mgmt_bundle(mgmt):
    print("==================================")
    print("Prepare %s" % mgmt)
    bundle_path = unzip([mgmt])[0]
    print("        bundle_path: %s" % bundle_path)
    copy_files_into(policies_path, bundle_path + "plugins")
    copy_files_into(resources_path, bundle_path + "plugins")
    copy_files_into(fetchers_path, bundle_path + "plugins")
    copy_files_into(repositories_path, bundle_path + "plugins",
                    [".*gravitee-repository-ehcache.*", ".*gravitee-apim-repository-gateway-bridge-http-client.*",
                     ".*gravitee-apim-repository-gateway-bridge-http-server.*", ".*gravitee-apim-repository-hazelcast.*",
                     ".*gravitee-apim-repository-redis.*"])
    copy_files_into(services_path, bundle_path + "plugins", [".*gravitee-gateway-services-ratelimit.*"])
    copy_files_into(connectors_path, bundle_path + "plugins")
    print("makedirs: %s" % (bundle_path + "plugins/ext/repository-jdbc"))
    os.makedirs(bundle_path + "plugins/ext/repository-jdbc", exist_ok=True)


def prepare_policies(version):
    print("==================================")
    print("Prepare Policies")
    dist_dir = get_dist_dir_name()
    policies_dist_path = "%s/%s/gravitee-policies-%s" % (tmp_path, dist_dir, version)
    os.makedirs(policies_dist_path, exist_ok=True)
    copy_files_into(policies_path, policies_dist_path)
    copy_files_into(services_path, policies_dist_path)


def package(version, release_json):
    print("==================================")
    print("Packaging")
    packages = []
    exclude_from_full_zip_list = [re.compile(".*graviteeio-policies.*")]
    dist_dir = get_dist_dir_name()
    full_zip_name = "graviteeio-full-%s" % version

    # how to create a symbolic link ?
    # if jdbc:
    #    full_zip_name = "graviteeio-full-jdbc-%s" % version

    full_zip_path = "%s/%s/%s.zip" % (tmp_path, dist_dir, full_zip_name)
    dirs = [os.path.join("%s/%s/" % (tmp_path, dist_dir), fn) for fn in next(os.walk("%s/%s/" % (tmp_path, dist_dir)))[1]]
    # add release.json
    jsonfile_name = "release.json"
    jsonfile_absname = os.path.join("%s/%s/%s" % (tmp_path, dist_dir, jsonfile_name))
    jsonfile = open(jsonfile_absname, "w")
    jsonfile.write("%s" % json.dumps(release_json, indent=4))
    jsonfile.close()
    with zipfile.ZipFile(full_zip_path, "w", zipfile.ZIP_DEFLATED) as full_zip:
        print("Create %s" % full_zip_path)
        packages.append(full_zip_path)

        full_zip.write(jsonfile_absname, jsonfile_name)
        for d in dirs:
            with zipfile.ZipFile("%s.zip" % d, "w", zipfile.ZIP_DEFLATED) as bundle_zip:
                print("Create %s.zip" % d)
                packages.append("%s.zip" % d)
                dir_abs_path = os.path.abspath(d)
                dir_name = os.path.split(dir_abs_path)[1]
                for dirname, subdirs, files in os.walk(dir_abs_path):
                    exclude_from_full_zip = False
                    for pattern in exclude_from_full_zip_list:
                        if pattern.match(d):
                            exclude_from_full_zip = True
                            break
                    for filename in files:
                        absname = os.path.abspath(os.path.join(dirname, filename))
                        arcname = absname[len(dir_abs_path) - len(dir_name):]
                        bundle_zip.write(absname, arcname)
                        if exclude_from_full_zip is False:
                            full_zip.write(absname, "%s/%s" % (full_zip_name, arcname))
                    if len(files) == 0:
                        absname = os.path.abspath(dirname)
                        arcname = absname[len(dir_abs_path) - len(dir_name):]
                        bundle_zip.write(absname, arcname)
                        if exclude_from_full_zip is False:
                            full_zip.write(absname, "%s/%s" % (full_zip_name, arcname))
    return packages


def rename(string):
    return string.replace("gravitee", "graviteeio") \
        .replace("management-standalone", "management-api") \
        .replace("management-webui", "management-ui") \
        .replace("portal-webui", "portal-ui") \
        .replace("standalone-", "")


def clean_dir_names():
    print("==================================")
    print("Clean directory names")
    dirs = [os.path.join("%s/%s/" % (tmp_path, get_dist_dir_name()), fn) for fn in
            next(os.walk("%s/%s/" % (tmp_path, get_dist_dir_name())))[1]]
    for d in dirs:
        os.rename(d, rename(d))


def response_pretty_print(r):
    print("###########################################################")
    print("STATUS %s" % r.status_code)
    print("HEADERS \n%s" % r.headers)
    print("RESPONSE \n%s" % r.text)
    print("###########################################################\n\n")
    r.raise_for_status()


def get_dist_dir_name():
    dist_dir = "dist"
    return dist_dir


def main():
    print(" # DEBUG NOW starting main()  ")
    if is_latest_param:
        release_json_url = "https://raw.githubusercontent.com/gravitee-io/release/master/release.json"
    else:
        release_json_url = "https://raw.githubusercontent.com/gravitee-io/release/%s/release.json" % version_param

    print(release_json_url)
    release_json = requests.get(release_json_url)
    print(release_json)
    release_json = release_json.json()
    version = release_json['version']

    print("Create bundles for Gravitee.io v%s" % version)
    clean()

    v3 = int(version[0]) > 1
    if v3:
        portal_ui = download_portal_ui(get_component_by_name(release_json, "gravitee-portal-webui"), version)
        mgmt_api = download_managementV3_api(get_component_by_name(release_json, "gravitee-api-management"), version)
    else:
        mgmt_api = download_management_api(get_component_by_name(release_json, "gravitee-management-rest-api"), version)

    ui = download_ui(get_component_by_name(release_json, "gravitee-management-webui"), version)
    gateway = download_gateway(get_component_by_name(release_json, "gravitee-gateway"), version)
    print(" # DEBUG NOW [ download_policies(get_policies(release_json)) ]  ")
    download_policies(get_policies(release_json))
    download_resources(get_resources(release_json))
    download_fetchers(get_fetchers(release_json))
    download_tracers(get_tracers(release_json))
    download_services(get_services(release_json))
    download_reporters(get_reporters(release_json))
    download_repositories(get_repositories(release_json))

    if int(version.replace(".", "").replace("-SNAPSHOT", "")) > 354:
        download_connectors(get_connectors(release_json))

    if v3:
        prepare_ui_bundle(portal_ui)

    prepare_gateway_bundle(gateway)
    prepare_ui_bundle(ui)
    prepare_mgmt_bundle(mgmt_api)
    prepare_policies(version)
    clean_dir_names()
    package(version, release_json)


main()
